/*
 * CyanTune 青调 —— 桌面 180° 旋转解锁（含 Android 17 hook 修复）
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * 迁移自 launcher-tweaks 旧仓。Android 17 (SDK 37) 真机日志显示旧版：
 *   [Rotation180Feature] hooked Activity.setRequestedOrientation OK
 *   [Rotation180Feature] ERROR: hook onAttachedToWindow FAILED
 * 根因：精确 hook Launcher.onAttachedToWindow 在 A17 上找不到该方法（方法被移动/签名变化）。
 *
 * 修复策略（多级容错，任一 hook 点失败只打 warning 继续，绝不整体放弃）：
 * 1. 类解析多级 fallback：
 *      com.android.launcher3.uioverrides.QuickstepLauncher  ← A17 LineageOS 真机真实入口
 *      com.android.launcher3.Launcher                       ← AOSP Launcher3 基类
 *      org.lineageos.trebuchet.Launcher                    ← Trebuchet 兜底类名
 *    全部用 findClassIfExists 软探测，谁存在用谁。
 * 2. setRequestedOrientation：hook android.app.Activity 基类（A17 已验证成功），
 *    仅当调用方是解析出的 Launcher 实例时，把 PORTRAIT/SENSOR_PORTRAIT/USER_PORTRAIT 改成 UNSPECIFIED。
 * 3. onAttachedToWindow：findMethodExactIfExists 软探测；
 *    存在 → hook 它；不存在（A17 情形）→ 降级 hook Activity.onResume（基类必有），
 *    在 afterHookedMethod 里判断 thisObject 是 Launcher 实例后置 requestedOrientation=UNSPECIFIED。
 *
 * 配置走 ConfigStore，受 launcher_rotation180.enabled 与 global.enabled 双开关控制。
 * 方向在 Activity 创建后修改需重启桌面才完全生效（onConfigChanged 里只打日志提醒）。
 */
package com.gtlx.cyantune.feature.launcher

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.gtlx.cyantune.core.ConfigStore
import com.gtlx.cyantune.core.Feature
import com.gtlx.cyantune.core.FeatureContext
import com.gtlx.cyantune.core.TuneLog

object Rotation180Feature : Feature {

    override val id = "launcher_rotation180"
    override val displayName = "桌面 180° 旋转"
    override val targetPackages = setOf(
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "org.lineageos.trebuchet",
    )

    /** 类解析 fallback 顺序：真机 QuickstepLauncher 优先 */
    private val LAUNCHER_CLASS_CANDIDATES = arrayOf(
        "com.android.launcher3.uioverrides.QuickstepLauncher",
        "com.android.launcher3.Launcher",
        "org.lineageos.trebuchet.Launcher",
    )

    /** 同一进程只安装一次（object 单例 + 标志位双保险） */
    private var hookInstalled = false
    private var configWatching = false

    /** 该功能当前是否应生效（总开关 + 子开关） */
    private fun active(): Boolean {
        val cfg = ConfigStore.current
        return cfg.globalEnabled && cfg.rotation180Enabled
    }

    override fun onHookLoadPackage(ctx: FeatureContext) {
        if (hookInstalled) return

        // ===== 1. 类解析多级 fallback =====
        var launcherClass: Class<*>? = null
        var resolvedName = ""
        for (name in LAUNCHER_CLASS_CANDIDATES) {
            val c = XposedHelpers.findClassIfExists(name, ctx.classLoader)
            if (c != null) {
                launcherClass = c
                resolvedName = name
                break
            }
        }
        if (launcherClass == null) {
            TuneLog.e("[Rotation180] no launcher class found in candidates, skip this hit")
            return
        }
        TuneLog.i("[Rotation180] resolved launcher class: $resolvedName")

        // ===== 2. hook Activity.setRequestedOrientation（基类，A17 已验证可 hook） =====
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "setRequestedOrientation",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!active()) return
                        // 只干预 Launcher 自己发起的调用，不影响桌面进程里的其他 Activity
                        if (!launcherClass.isInstance(param.thisObject)) return
                        val orientation = param.args[0] as Int
                        if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                            orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
                            orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        ) {
                            TuneLog.i("[Rotation180] intercept setRequestedOrientation: " +
                                    "${orientationName(orientation)} → UNSPECIFIED")
                            param.args[0] = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                }
            )
            TuneLog.i("[Rotation180] hooked Activity.setRequestedOrientation OK")
        } catch (t: Throwable) {
            // 单点失败只警告，继续尝试后面的 hook 点
            TuneLog.e("[Rotation180] hook setRequestedOrientation FAILED (continue anyway)", t)
        }

        // 从 Launcher.onCreate(after) 拿 Context 启动配置读取（Application.onCreate hook 不可靠）
        hookLauncherOnCreateForContext(launcherClass)

        // ===== 3. onAttachedToWindow 软探测；缺失则降级 Activity.onResume =====
        val attached = XposedHelpers.findMethodExactIfExists(launcherClass, "onAttachedToWindow")
        if (attached != null) {
            try {
                XposedBridge.hookMethod(attached, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!active()) return
                        ensureUnspecified(param.thisObject)
                        TuneLog.i("[Rotation180] Launcher.onAttachedToWindow → UNSPECIFIED")
                    }
                })
                TuneLog.i("[Rotation180] hooked Launcher.onAttachedToWindow OK")
            } catch (t: Throwable) {
                TuneLog.e("[Rotation180] hook onAttachedToWindow failed, falling back to onResume", t)
                hookOnResumeFallback(launcherClass)
            }
        } else {
            // Android 17 情形：方法已不存在/签名变化
            TuneLog.i("[Rotation180] onAttachedToWindow not found on $resolvedName, " +
                    "onResume fallback active")
            hookOnResumeFallback(launcherClass)
        }

        hookInstalled = true
        TuneLog.i("[Rotation180] init OK (target=${ctx.packageName})")
    }

    /**
     * 降级路径：hook Activity.onResume（基类必有），
     * 仅当 thisObject 是解析出的 Launcher 实例时设置 UNSPECIFIED。
     */
    private fun hookOnResumeFallback(launcherClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!active()) return
                        if (!launcherClass.isInstance(param.thisObject)) return
                        ensureUnspecified(param.thisObject)
                    }
                }
            )
            TuneLog.i("[Rotation180] hooked Activity.onResume fallback OK")
        } catch (t: Throwable) {
            TuneLog.e("[Rotation180] onResume fallback FAILED", t)
        }
    }

    /** hook Launcher.onCreate(after)：拿 Context 启动配置读取/热更新（找不到就静默跳过） */
    private fun hookLauncherOnCreateForContext(launcherClass: Class<*>) {
        val onCreate = XposedHelpers.findMethodExactIfExists(
            launcherClass, "onCreate", Bundle::class.java
        ) ?: return
        try {
            XposedBridge.hookMethod(onCreate, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!configWatching) {
                        configWatching = true
                        ConfigStore.loadAndWatch(activity.applicationContext) { }
                    }
                    // onAttached 路径也依赖配置；onCreate 后先尝试置一次
                    if (active()) ensureUnspecified(activity)
                }
            })
            TuneLog.i("[Rotation180] hooked Launcher.onCreate for context OK")
        } catch (t: Throwable) {
            TuneLog.e("[Rotation180] hook onCreate(for context) failed, continue", t)
        }
    }

    override fun onConfigChanged(ctx: FeatureContext) {
        // 已创建的 Activity 方向需重启桌面才完全生效；热更新只保证后续调用被正确拦截
        val cfg = ConfigStore.current
        TuneLog.i("[Rotation180] config updated (global=${cfg.globalEnabled}, " +
                "rot180=${cfg.rotation180Enabled}). Restart launcher to apply fully.")
    }

    /** 把目标 Activity 的 requestedOrientation 置为 UNSPECIFIED（失败不崩） */
    private fun ensureUnspecified(obj: Any?) {
        val activity = obj as? Activity ?: return
        try {
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (t: Throwable) {
            TuneLog.e("[Rotation180] set requestedOrientation failed", t)
        }
    }

    private fun orientationName(orientation: Int): String = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> "UNSPECIFIED"
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "PORTRAIT"
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "LANDSCAPE"
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "SENSOR_PORTRAIT"
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "SENSOR_LANDSCAPE"
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> "USER_PORTRAIT"
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> "USER_LANDSCAPE"
        else -> "UNKNOWN($orientation)"
    }
}
