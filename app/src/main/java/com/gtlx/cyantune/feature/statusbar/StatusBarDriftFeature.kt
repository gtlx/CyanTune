/*
 * CyanTune 青调 —— 状态栏水平漂移（防烧屏）
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * 迁移自 status-bar-drift 旧仓（已在真机验证可跑），改动：
 * - 配置改走 ConfigStore（root 全局配置中心），不再用 ContentProvider
 * - 受 statusbar_drift.enabled 与 global.enabled 双开关控制
 * - 实现 Feature 插件契约
 *
 * 成熟做法沿用：不 hook Application.onCreate（SystemUI 上不可靠），
 * 而是 hook PhoneStatusBarView 的 onAttachedToWindow/onDetachedFromWindow，
 * 从 View 上拿 Context；多 classLoader 二次命中时 findClass 可能失败，容错跳过。
 */
package com.gtlx.cyantune.feature.statusbar

import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.gtlx.cyantune.core.ConfigStore
import com.gtlx.cyantune.core.Feature
import com.gtlx.cyantune.core.FeatureContext
import com.gtlx.cyantune.core.TuneConfig
import com.gtlx.cyantune.core.TuneLog

object StatusBarDriftFeature : Feature {

    override val id = "statusbar_drift"
    override val displayName = "状态栏漂移"
    override val targetPackages = setOf("com.android.systemui")

    private const val VIEW_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    private val handler = Handler(Looper.getMainLooper())
    private var statusBarView: View? = null
    private var direction = 1

    @Volatile
    private var running = false
    private var configWatching = false

    /** 该功能当前是否应生效（总开关 + 子开关） */
    private fun active(cfg: TuneConfig) = cfg.globalEnabled && cfg.driftEnabled

    private val driftRunnable = object : Runnable {
        override fun run() {
            val view = statusBarView
            if (view == null) {
                TuneLog.d("[StatusBarDrift] drift skip: view null")
                return
            }
            val cfg = ConfigStore.current
            if (!active(cfg)) return // 总开关关闭时不继续排程
            val target = if (direction > 0) cfg.driftPx.toFloat() else -cfg.driftPx.toFloat()
            direction = -direction
            try {
                view.translationX = target
            } catch (t: Throwable) {
                TuneLog.e("[StatusBarDrift] drift error", t)
                return
            }
            TuneLog.i("[StatusBarDrift] drift → ${target}px")
            handler.postDelayed(this, cfg.driftIntervalSec * 1000L)
        }
    }

    override fun onHookLoadPackage(ctx: FeatureContext) {
        // 多 classLoader 场景下可能出现第二次命中且找不到该类，失败只跳过，不崩
        val viewClass = XposedHelpers.findClassIfExists(VIEW_CLASS, ctx.classLoader)
        if (viewClass == null) {
            TuneLog.i("[StatusBarDrift] $VIEW_CLASS not found in this classLoader, skip")
            return
        }

        XposedHelpers.findAndHookMethod(viewClass, "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (!configWatching) {
                        configWatching = true
                        // 从 View 拿 Context（旧仓验证可靠），启动配置读取 + 热更新监听
                        ConfigStore.loadAndWatch(view.context.applicationContext) { }
                    }
                    view.post { onViewAttached(view) }
                }
            })

        XposedHelpers.findAndHookMethod(viewClass, "onDetachedFromWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    onViewDetached(param.thisObject as View)
                }
            })

        TuneLog.i("[StatusBarDrift] hooked PhoneStatusBarView OK")
    }

    override fun onConfigChanged(ctx: FeatureContext) {
        val cfg = ConfigStore.current
        if (!active(cfg)) {
            stopDrifting(statusBarView)
            TuneLog.i("[StatusBarDrift] disabled by config, drift stopped")
            return
        }
        // 运行中：用新幅度/周期重启循环；
        // View 已挂载但循环未运行（如刚从关闭切到开启）：直接恢复漂移
        val view = statusBarView
        if (running && view != null) {
            handler.removeCallbacks(driftRunnable)
            handler.postDelayed(driftRunnable, cfg.driftIntervalSec * 1000L)
            TuneLog.i("[StatusBarDrift] cycle restarted (${cfg.driftPx}px/${cfg.driftIntervalSec}s)")
        } else if (!running && view != null && view.width > 0) {
            startDrifting(view)
        }
    }

    /** View 挂载：未测量完时 post 重试（旧仓成熟逻辑） */
    private fun onViewAttached(view: View) {
        if (view.width <= 0) {
            view.post { onViewAttached(view) }
            return
        }
        if (!active(ConfigStore.current)) return
        startDrifting(view)
    }

    private fun onViewDetached(view: View) = stopDrifting(view)

    @Synchronized
    private fun startDrifting(view: View) {
        if (statusBarView === view && running) return
        stopDrifting(statusBarView)
        statusBarView = view
        running = true
        val cfg = ConfigStore.current
        TuneLog.i("[StatusBarDrift] ▶ start drifting (w=${view.width} h=${view.height}, " +
                "${cfg.driftPx}px/${cfg.driftIntervalSec}s)")
        handler.removeCallbacks(driftRunnable)
        handler.postDelayed(driftRunnable, cfg.driftIntervalSec * 1000L)
    }

    @Synchronized
    private fun stopDrifting(view: View?) {
        handler.removeCallbacks(driftRunnable)
        if (view != null) {
            try { view.translationX = 0f } catch (_: Throwable) {}
        }
        if (statusBarView === view || view == null) {
            val wasRunning = running
            statusBarView = null
            running = false
            if (wasRunning) {
                TuneLog.i("[StatusBarDrift] ■ stop drifting")
            }
        }
    }
}
