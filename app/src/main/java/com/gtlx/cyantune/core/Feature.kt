/*
 * CyanTune 青调 —— 插件化系统调教
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Feature 插件契约：每个可调教功能实现本接口，并在 FeatureRegistry 注册。
 * 新增功能 = 新建一个实现类 + Registry 加一行，框架（MainHook）零改动。
 */
package com.gtlx.cyantune.core

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 一个可调教功能（插件）。
 *
 * 每个 Feature 自声明：
 * - [id]             稳定唯一标识，同时作为配置 key 前缀（如 "statusbar_drift"）
 * - [targetPackages] 需要注入哪些进程；MainHook 只在命中进程里调用 [onHookLoadPackage]
 *
 * 生命周期（在目标进程内）：
 * 1. [onHookLoadPackage] 进程加载命中包时调用，在这里注册 Xposed hook（不要假设 Context 已就绪）
 * 2. Feature 在自己 hook 到 View/Activity 时自行获取 Context，并调用 ConfigStore.attachFallbackContext
 * 3. 配置热更新时 [onConfigChanged] 在主线程被回调
 */
interface Feature {
    /** 稳定唯一 id，例如 "statusbar_drift"，同时是配置 key 前缀 */
    val id: String

    /** UI 设置页显示名 */
    val displayName: String

    /** 需要注入的目标进程包名；命中其一即在该进程初始化 */
    val targetPackages: Set<String>

    /**
     * 在命中进程注册 hook。
     *
     * 注意：此时拿不到可靠的 Application Context（旧仓已验证 hook Application.onCreate
     * 在 SystemUI/Launcher 不可靠）。Context 请在自己 hook 到的 View/Activity 上取。
     */
    fun onHookLoadPackage(ctx: FeatureContext)

    /** 配置文件热更新回调（主线程）；enabled 状态变化等在这里处理 */
    fun onConfigChanged(ctx: FeatureContext) {}
}

/**
 * 传给每个 Feature 的运行时上下文，屏蔽框架细节。
 *
 * 不强依赖 Application Context：读配置走 [ConfigStore]（读文件不需要 Context）；
 * SELinux fallback 才需要 Context，由 Feature 拿到 View/Activity Context 后补登记。
 */
class FeatureContext(
    val packageName: String,
    val classLoader: ClassLoader,
    val lpparam: XC_LoadPackage.LoadPackageParam,
)
