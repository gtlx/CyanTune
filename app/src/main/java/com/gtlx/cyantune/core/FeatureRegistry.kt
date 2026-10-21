/*
 * CyanTune 青调 —— Feature 注册表
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * 插件化验收红线落地点：新增功能只需
 *   1. 新建 com.gtlx.cyantune.feature.xxx.XxxFeature（实现 Feature）
 *   2. 在下面 FEATURES 列表里加一行
 * MainHook / ConfigStore / 其他 Feature 均无需任何改动。
 */
package com.gtlx.cyantune.core

import com.gtlx.cyantune.feature.launcher.Rotation180Feature
import com.gtlx.cyantune.feature.pocket.PocketGuardFeature
import com.gtlx.cyantune.feature.statusbar.StatusBarDriftFeature

object FeatureRegistry {

    /** 全部已注册插件（显式列表，进程一启动即确定，无反射扫描） */
    val FEATURES: List<Feature> = listOf(
        StatusBarDriftFeature,
        Rotation180Feature,
        PocketGuardFeature,
        // ↑ 新增功能在这里加一行即可
    )

    /** 按进程包名反查命中的 feature（一个进程可命中多个） */
    fun featuresForPackage(pkg: String): List<Feature> =
        FEATURES.filter { pkg in it.targetPackages }
}
