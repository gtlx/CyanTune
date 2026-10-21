/*
 * CyanTune 青调 —— 口袋防误触（SystemUI / Keyguard 层）
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * 行为：仅在「锁屏 + 亮屏 + 距离传感器近距」时，向锁屏窗口根 ViewGroup 注入一个
 * 全屏透明拦截 View（消费全部触摸事件）防误触；远距 / 解锁 / 来电响铃立即移除。
 *
 * 稳为先：
 * - 注入点全部 findClassIfExists 软探测，多级 fallback；全部找不到则 warn 安全降级，绝不崩 SystemUI
 * - 传感器只在锁屏可见时 register，其余时间 unregister，避免常驻耗电
 * - 来电响铃 CALL_STATE_RINGING 强制白名单放行（必须能滑动接听）
 * - 受 global.enabled 与 pocket.enabled 双开关控制；onConfigChanged 关闭时立即撤遮罩+反注册
 *
 * 注入点候选（A17 类名可能变动，按顺序只 hook 第一个存在的类，选中后记为 selectedHost）：
 *   com.android.systemui.shade.NotificationShadeWindowView   （A14+ 锁屏/下拉窗口根，覆盖最全，首选）
 *   com.android.systemui.statusbar.phone.NotificationShadeWindowView（旧包名兜底）
 *   com.android.systemui.keyguard.KeyguardRootView
 *   com.android.keyguard.KeyguardHostView
 * 真机实际命中类会以 [PocketGuard] selected host: <name> 打日志。
 * attach/detach 各自独立软探测（findMethodExactIfExists）：A17 上 onDetachedFromWindow 精确签名
 * 可能缺失，detach 缺失只跳过（靠 1s 轮询 isAttachedToWindow 清引用），绝不连累 attach。
 * Vector 多 classLoader 多次命中：以 hookInstalled 保证只在真正找到类且 attach 成功的那次安装一次。
 */
package com.gtlx.cyantune.feature.pocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.KeyguardManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.gtlx.cyantune.core.ConfigStore
import com.gtlx.cyantune.core.Feature
import com.gtlx.cyantune.core.FeatureContext
import com.gtlx.cyantune.core.TuneConfig
import com.gtlx.cyantune.core.TuneLog

object PocketGuardFeature : Feature {

    override val id = "pocket"
    override val displayName = "口袋防误触"
    override val targetPackages = setOf("com.android.systemui")

    /** 注入点候选，按优先级顺序（越靠前覆盖越全） */
    private val HOST_CANDIDATES = arrayOf(
        "com.android.systemui.shade.NotificationShadeWindowView",
        "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
        "com.android.systemui.keyguard.KeyguardRootView",
        "com.android.keyguard.KeyguardHostView",
    )

    /** 环境光二次确认阈值（lux），低于此值才算「暗口袋」 */
    private const val LIGHT_GATE_LUX = 5f

    private val handler = Handler(Looper.getMainLooper())

    // ===== 系统服务 / 传感器 =====
    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var proximity: Sensor? = null
    private var light: Sensor? = null
    private var keyguardManager: KeyguardManager? = null
    private var powerManager: PowerManager? = null
    private var telephony: TelephonyManager? = null
    private var phoneListener: PhoneStateListener? = null

    /** 已 hook 到并挂载过的宿主：view → 注入点类名 */
    private val hosts = LinkedHashMap<View, String>()
    private var currentCover: View? = null
    private var coverParent: ViewGroup? = null

    @Volatile private var near = false
    @Volatile private var lightLux = Float.NaN
    @Volatile private var ringing = false
    private var sensorListening = false
    private var receiversRegistered = false
    private var configWatching = false
    private var lightGateWarned = false

    /** 多 classLoader 多次命中时，保证只在「真正找到类的那次」安装一次 */
    @Volatile private var hookInstalled = false
    /** 实际选中并 hook 的宿主类名（只 hook 首个存在的候选） */
    @Volatile private var selectedHost: String? = null

    private fun active(cfg: TuneConfig = ConfigStore.current) =
        cfg.globalEnabled && cfg.pocketEnabled

    // ===== Feature 契约 =====

    override fun onHookLoadPackage(ctx: FeatureContext) {
        if (hookInstalled) return
        // 只选第一个存在的候选类，后续候选不再 hook
        var hostClass: Class<*>? = null
        var resolvedName: String? = null
        for (name in HOST_CANDIDATES) {
            val c = XposedHelpers.findClassIfExists(name, ctx.classLoader) ?: continue
            hostClass = c
            resolvedName = name
            break
        }
        if (hostClass == null) {
            // Vector 多 classLoader 会多次命中，找不到类的那次静默 return（降为 debug，不刷吓人 error）；
            // 首次没找到、后续 classLoader 命中找到了，仍允许安装（以真正安装成功为准）。
            TuneLog.d("[PocketGuard] no injection point in this classLoader, wait for next hit")
            return
        }
        // attach 是必须的：装不上则本次不算成功，留给后续 classLoader 命中再试
        if (!hookAttach(hostClass, resolvedName!!)) return
        hookDetach(hostClass, resolvedName)
        hookInstalled = true
        selectedHost = resolvedName
        TuneLog.i("[PocketGuard] selected host: $resolvedName")
        // 周期复核：锁屏状态/来电状态变化不一定都有回调，1s 轮询兜底（仅触发状态机，无传感器时常驻空转）
        handler.postDelayed(object : Runnable {
            override fun run() {
                pruneDetachedHosts()
                reevaluate()
                handler.postDelayed(this, 1000L)
            }
        }, 1000L)
        TuneLog.i("[PocketGuard] init OK (target=${ctx.packageName})")
    }

    override fun onConfigChanged(ctx: FeatureContext) {
        val cfg = ConfigStore.current
        TuneLog.i("[PocketGuard] config updated: global=${cfg.globalEnabled}, " +
                "pocket=${cfg.pocketEnabled}, lightGate=${cfg.pocketUseLightGate}")
        // 关闭：立即移除遮罩 + 反注册传感器
        if (!active(cfg)) {
            removeCover("disabled by config")
            stopSensors()
        }
        reevaluate()
    }

    // ===== hook 宿主视图 =====

    /**
     * attach（必须）：findMethodExactIfExists 软探测，存在才 XposedBridge.hookMethod。
     * 与 detach 完全独立，互不连坐。返回是否安装成功。
     */
    private fun hookAttach(cls: Class<*>, name: String): Boolean {
        val m = XposedHelpers.findMethodExactIfExists(cls, "onAttachedToWindow")
        if (m == null) {
            TuneLog.e("[PocketGuard] $name.onAttachedToWindow not found, skip")
            return false
        }
        return try {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    hosts[v] = name
                    TuneLog.i("[PocketGuard] hook injection point: $name (attached)")
                    initServices(v.context.applicationContext)
                    v.post { reevaluate() }
                }
            })
            TuneLog.i("[PocketGuard] hooked $name.onAttachedToWindow OK")
            true
        } catch (t: Throwable) {
            TuneLog.e("[PocketGuard] hook $name.onAttachedToWindow failed", t)
            false
        }
    }

    /**
     * detach（可选）：onDetachedFromWindow 与 onFinishTemporaryDetach 各自独立软探测，
     * 任一缺失只打 debug 跳过，绝不影响 attach。两者都拿不到时，靠每秒轮询
     * pruneDetachedHosts() + isAttachedToWindow 判断清引用，不崩。
     */
    private fun hookDetach(cls: Class<*>, name: String) {
        hookOneDetach(cls, name, "onDetachedFromWindow")
        hookOneDetach(cls, name, "onFinishTemporaryDetach")
    }

    private fun hookOneDetach(cls: Class<*>, name: String, method: String) {
        val m = XposedHelpers.findMethodExactIfExists(cls, method)
        if (m == null) {
            TuneLog.d("[PocketGuard] $name.$method not found, skip")
            return
        }
        try {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    hosts.remove(v)
                    // 遮罩若挂在它上面，随窗口一起销毁，清引用即可
                    if (coverParent === v) {
                        currentCover = null
                        coverParent = null
                    }
                    reevaluate()
                }
            })
            TuneLog.i("[PocketGuard] hooked $name.$method OK")
        } catch (t: Throwable) {
            TuneLog.d("[PocketGuard] hook $name.$method failed (${t.javaClass.simpleName}), " +
                    "skip (poll fallback)")
        }
    }

    /** detach hook 缺失时的轮询兜底：清掉已不在窗口上的宿主引用 */
    private fun pruneDetachedHosts() {
        if (hosts.isEmpty()) return
        val it = hosts.keys.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (!v.isAttachedToWindow) {
                it.remove()
                if (coverParent === v) {
                    currentCover = null
                    coverParent = null
                }
            }
        }
    }

    // ===== 服务初始化（只做一次） =====

    private fun initServices(ctx: Context) {
        if (appContext === ctx) return
        appContext = ctx
        try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager = sm
            if (sm != null) {
                // 默认 proximity；真机有 Non-wakeup(0x51)/Wakeup(0x52) 两个，优先 wakeup（息屏也能上报）
                var prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                try {
                    val wakeup = sm.getSensorList(Sensor.TYPE_PROXIMITY)
                        .firstOrNull { it.isWakeUpSensor }
                    if (wakeup != null) prox = wakeup
                } catch (_: Throwable) { /* isWakeUpSensor 旧平台无此方法，用默认即可 */ }
                proximity = prox
                light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
                TuneLog.i("[PocketGuard] proximity=${prox?.name} " +
                        "(maxRange=${proximity?.maximumRange}), light=${light?.name}")
            }
            keyguardManager = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            // 来电白名单：PhoneStateListener（SystemUI 自身有 READ_PHONE_STATE，失败不致命，轮询兜底）
            try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephony = tm
                if (tm != null) {
                    val l = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            val wasRinging = ringing
                            ringing = state == TelephonyManager.CALL_STATE_RINGING
                            if (ringing) TuneLog.i("[PocketGuard] ringing, bypass")
                            if (wasRinging != ringing) reevaluate()
                        }
                    }
                    phoneListener = l
                    tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                }
            } catch (t: Throwable) {
                TuneLog.e("[PocketGuard] PhoneStateListener unavailable, rely on polling", t)
            }
            if (!receiversRegistered) {
                receiversRegistered = true
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                // 系统广播用 NOT_EXPORTED 注册在特权进程安全
                ctx.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        TuneLog.d("[PocketGuard] screen/user event: ${intent?.action}")
                        reevaluate()
                    }
                }, filter, Context.RECEIVER_NOT_EXPORTED)
            }
            if (!configWatching) {
                configWatching = true
                ConfigStore.loadAndWatch(ctx) { }
            }
        } catch (t: Throwable) {
            TuneLog.e("[PocketGuard] initServices failed", t)
        }
    }

    // ===== 状态机（主线程） =====

    private fun isScreenOn(): Boolean = try {
        powerManager?.isInteractive ?: true
    } catch (_: Throwable) { true }

    private fun isLocked(): Boolean = try {
        keyguardManager?.isKeyguardLocked ?: false
    } catch (_: Throwable) { false }

    /** 轮询兜底：某些 ROM 上 PhoneStateListener 不回调 */
    private fun pollCallState() {
        try {
            val tm = telephony ?: return
            val r = tm.callState == TelephonyManager.CALL_STATE_RINGING
            if (r != ringing) {
                ringing = r
                if (r) TuneLog.i("[PocketGuard] ringing, bypass (poll)")
            }
        } catch (_: Throwable) {}
    }

    private fun reevaluate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { reevaluate() }
            return
        }
        pollCallState()
        val cfg = ConfigStore.current
        if (!active(cfg)) {
            removeCover("inactive")
            stopSensors()
            return
        }

        val locked = isLocked()
        val screenOn = isScreenOn()
        val shouldListen = locked && screenOn && !ringing

        if (shouldListen) {
            startSensors()
        } else {
            stopSensors()
            near = false
        }

        // 白名单：响铃必须放行
        if (ringing) {
            removeCover("ringing,bypass")
            return
        }
        if (!locked || !screenOn) {
            removeCover(if (!locked) "unlocked" else "screen off")
            return
        }

        val lightOk = if (cfg.pocketUseLightGate) {
            val lux = lightLux
            when {
                light == null -> {
                    if (!lightGateWarned) {
                        lightGateWarned = true
                        TuneLog.e("[PocketGuard] light gate enabled but no light sensor; " +
                                "will not cover")
                    }
                    false
                }
                lux.isNaN() -> false // 尚无读数，等一帧，不误盖
                else -> lux < LIGHT_GATE_LUX
            }
        } else true

        if (near && lightOk) showCover() else removeCover("far → uncover")
    }

    // ===== 传感器生命周期：仅锁屏可见时注册 =====

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_PROXIMITY -> {
                    val max = proximity?.maximumRange ?: 5f
                    val v = event.values?.getOrNull(0) ?: return
                    val isNear = v < max || v == 0f
                    if (isNear != near) {
                        near = isNear
                        TuneLog.i(if (isNear) "[PocketGuard] near → cover"
                                  else "[PocketGuard] far → uncover")
                    }
                    reevaluate()
                }
                Sensor.TYPE_LIGHT -> {
                    lightLux = event.values?.getOrNull(0) ?: Float.NaN
                    if (ConfigStore.current.pocketUseLightGate) reevaluate()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startSensors() {
        val sm = sensorManager ?: return
        val prox = proximity ?: run {
            if (!lightGateWarned) {
                TuneLog.e("[PocketGuard] no proximity sensor available, inactive")
            }
            return
        }
        if (sensorListening) return
        sensorListening = true
        // 主线程监听，UI 操作无需再切线程；唤醒传感器用 GAME 采样率即可
        sm.registerListener(sensorListener, prox, SensorManager.SENSOR_DELAY_UI)
        val cfg = ConfigStore.current
        if (cfg.pocketUseLightGate && light != null) {
            sm.registerListener(sensorListener, light, SensorManager.SENSOR_DELAY_UI)
        }
        TuneLog.i("[PocketGuard] sensors registered (keyguard visible)")
    }

    private fun stopSensors() {
        val sm = sensorManager ?: return
        if (!sensorListening) return
        sensorListening = false
        sm.unregisterListener(sensorListener)
        TuneLog.i("[PocketGuard] sensors unregistered")
    }

    // ===== 遮罩注入 / 移除 =====

    private fun bestHost(): ViewGroup? {
        // 只 hook 了首个存在的候选类，优先从该宿主里选当前已挂载、可见者
        val sel = selectedHost
        if (sel != null) {
            val v = hosts.entries.firstOrNull { it.value == sel }?.key
            if (v is ViewGroup && v.isAttachedToWindow && v.visibility == View.VISIBLE) return v
        }
        return hosts.keys.firstOrNull { it is ViewGroup && it.isAttachedToWindow } as? ViewGroup
    }

    private fun showCover() {
        val existing = currentCover
        if (existing != null && existing.parent === coverParent && coverParent?.isAttachedToWindow == true) {
            existing.bringToFront()
            return
        }
        val parent = bestHost()
        if (parent == null) {
            // 宿主暂时不可用，下个 tick 再试，不报错刷屏
            return
        }
        try {
            val ctx = parent.context
            val cover = View(ctx).apply {
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                // 透明，但盖住并消费触摸
                setBackgroundColor(0x00000000)
                setOnTouchListener { _, _ ->
                    // 消费全部触摸（含 DOWN/MOVE/UP/CANCEL），防锁屏误触
                    true
                }
                translationZ = 999_999f
            }
            val lp = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            parent.addView(cover, lp)
            cover.bringToFront()
            currentCover = cover
            coverParent = parent
            TuneLog.i("[PocketGuard] cover added on ${hosts[parent]}")
        } catch (t: Throwable) {
            TuneLog.e("[PocketGuard] addView cover failed", t)
        }
    }

    private fun removeCover(reason: String) {
        val cover = currentCover
        val parent = coverParent
        if (cover == null) return
        currentCover = null
        coverParent = null
        try {
            if (cover.parent === parent) parent?.removeView(cover)
            else (cover.parent as? ViewGroup)?.removeView(cover)
        } catch (t: Throwable) {
            TuneLog.e("[PocketGuard] removeView cover failed", t)
        }
        TuneLog.i("[PocketGuard] cover removed ($reason)")
    }
}
