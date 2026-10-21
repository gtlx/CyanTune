/*
 * CyanTune 青调 —— 唯一 Xposed 入口
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * MainHook 只做路由，不含任何功能逻辑：
 *   1. 按当前进程包名从 FeatureRegistry 取命中的 Feature
 *   2. 逐个调用 onHookLoadPackage 注册 hook
 *   3. ConfigStore FileObserver 收到配置热更新后，回调命中 Feature 的 onConfigChanged
 */
package com.gtlx.cyantune

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.gtlx.cyantune.core.ConfigStore
import com.gtlx.cyantune.core.FeatureContext
import com.gtlx.cyantune.core.FeatureRegistry
import com.gtlx.cyantune.core.TuneLog

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val features = FeatureRegistry.featuresForPackage(pkg)
        if (features.isEmpty()) return

        TuneLog.i("=== target hit: $pkg, features=${features.map { it.id }} ===")

        val ctx = FeatureContext(
            packageName = pkg,
            classLoader = lpparam.classLoader,
            lpparam = lpparam,
        )

        // 各 Feature 自行注册 hook；单个 Feature 失败不影响其他 Feature
        for (feature in features) {
            try {
                feature.onHookLoadPackage(ctx)
            } catch (t: Throwable) {
                TuneLog.e("feature ${feature.id} onHookLoadPackage FAILED", t)
            }
        }

        // 配置热更新统一由 ConfigStore 的 FileObserver 分发到本进程命中的 Feature。
        // ConfigStore 的读取不需要 Context；fallback Context 由各 Feature 在 hook 点补登记。
        // 这里用一个轻量监听做分发（具体进程内 ConfigStore.loadAndWatch 由首个拿到
        // Context 的 Feature 触发；为不重复注册，ConfigStore 内部对 observer 做了单例保护）。
        HotReload.bind(ctx, features)
    }

    /**
     * 进程内热更新分发器：等待任一 Feature 触发 loadAndWatch 后即可收到回调。
     * 简化做法：直接在 ConfigStore 监听列表里挂分发（ConfigStore 的 watch 在 Feature
     * 拿到 Context 后才启动，启动前的监听者会在首次 reload 后随变更被通知）。
     */
    object HotReload {
        fun bind(ctx: FeatureContext, features: List<com.gtlx.cyantune.core.Feature>) {
            ConfigStore.addChangeListener {
                for (feature in features) {
                    try {
                        feature.onConfigChanged(ctx)
                    } catch (t: Throwable) {
                        TuneLog.e("feature ${feature.id} onConfigChanged FAILED", t)
                    }
                }
            }
        }
    }
}
