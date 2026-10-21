/*
 * CyanTune 青调 —— root 全局配置中心
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * 单一全局配置文件，所有目标进程（SystemUI/Launcher…）读同一份：
 *
 * 写入（仅 App UI 进程，经 RootBridge 一次 su 写两份，base64 传输避免 su 换行坑）：
 *   1. 主持久文件 /data/adb/cyantune/config.conf      （目录 0711，文件 0644，root 拥有）
 *   2. 全局可读副本 /data/local/tmp/cyantune_config.conf（0644，SystemUI/Launcher 等 uid 可读）
 * 同时额外写一份 packageContext fallback 副本（App filesDir，644，上层目录 711）。
 *
 * 读取（目标进程，免 root）：
 *   优先读 /data/local/tmp 副本；读不到/被 SELinux 拒绝 → createPackageContext 读 App filesDir；
 *   再失败 → 内置默认值。两条通道封装在本类内部，对 Feature 透明。
 *
 * 热更新：对 tmp 副本注册 FileObserver（CLOSE_WRITE / MOVED_TO），reload 后回调监听者。
 */
package com.gtlx.cyantune.core

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Properties

/** 配置数据快照（不可变），key 常量集中在此 */
data class TuneConfig(
    val globalEnabled: Boolean,
    val driftEnabled: Boolean,
    val driftPx: Int,
    val driftIntervalSec: Int,
    val rotation180Enabled: Boolean,
    val pocketEnabled: Boolean,
    val pocketUseLightGate: Boolean,
) {
    companion object {
        // ===== Properties key 常量（feature id 即前缀） =====
        const val KEY_GLOBAL_ENABLED = "global.enabled"

        const val KEY_DRIFT_ENABLED = "statusbar_drift.enabled"
        const val KEY_DRIFT_PX = "statusbar_drift.px"
        const val KEY_DRIFT_INTERVAL_SEC = "statusbar_drift.interval_sec"

        const val KEY_ROTATION180_ENABLED = "launcher_rotation180.enabled"

        const val KEY_POCKET_ENABLED = "pocket.enabled"
        const val KEY_POCKET_USE_LIGHT_GATE = "pocket.use_light_gate"

        /** 内置默认值（读不到任何通道时使用） */
        val DEFAULT = TuneConfig(
            globalEnabled = true,
            driftEnabled = true,
            driftPx = 4,
            driftIntervalSec = 60,
            rotation180Enabled = true,
            // 口袋防误触默认开启；暗光二次确认默认关闭（单近距即判定）
            pocketEnabled = true,
            pocketUseLightGate = false,
        )
    }
}

object ConfigStore {
    private const val PKG = "com.gtlx.cyantune"

    /** root 主持久文件 */
    private const val ROOT_DIR = "/data/adb/cyantune"
    private const val ROOT_FILE = "$ROOT_DIR/config.conf"

    /** 全局可读副本（目标进程主读通道） */
    private const val TMP_FILE = "/data/local/tmp/cyantune_config.conf"

    /** packageContext fallback：App 私有目录内的文件名 */
    private const val FALLBACK_FILE = "cyantune_config.conf"

    @Volatile
    private var snapshot: TuneConfig = TuneConfig.DEFAULT

    /** 当前配置快照（内存值，读文件后更新） */
    val current: TuneConfig get() = snapshot

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private var observer: FileObserver? = null
    private var observerStarted = false

    // ===== App UI 进程：保存（root 写两处 + fallback 副本） =====

    /**
     * 由 UI 调用：把配置持久化。同步执行（UI 应放在后台线程）。
     * @throws Exception root 不可用或 su 写入失败时抛出，UI 需提示“可能未授 root”
     */
    fun saveFromUi(appContext: Context, cfg: TuneConfig) {
        val content = encode(cfg)

        // 1. 先写 App filesDir fallback 副本（免 root，保证 packageContext 通道总有数据）
        val fbFile = File(appContext.filesDir, FALLBACK_FILE)
        fbFile.writeBytes(content.toByteArray(Charsets.UTF_8))
        // 文件 644、上层目录 711，供其他进程 createPackageContext(CONTEXT_IGNORE_SECURITY) 读取
        runCatching {
            fbFile.setReadable(true, false)
            appContext.filesDir.setExecutable(true, false)
            appContext.filesDir.setReadable(true, false)
            // dataDir 也需可进入
            appContext.applicationInfo.dataDir?.let { File(it).setExecutable(true, false) }
        }

        // 2. root 写主持久文件 + tmp 全局可读副本（base64 传输，避免 su 下中文/换行被吞）
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val script = buildString {
            append("mkdir -p ").append(ROOT_DIR).append('\n')
            append("echo '").append(b64).append("' | base64 -d > ").append(ROOT_FILE).append('\n')
            append("chmod 711 ").append(ROOT_DIR).append('\n')
            append("chmod 644 ").append(ROOT_FILE).append('\n')
            append("echo '").append(b64).append("' | base64 -d > ").append(TMP_FILE).append('\n')
            append("chmod 644 ").append(TMP_FILE).append('\n')
        }
        RootBridge.execScript(script, timeoutMs = 10_000)

        snapshot = cfg
        TuneLog.i("config saved via root: $ROOT_FILE + $TMP_FILE")
    }

    /** App UI 进程：读回当前生效配置（优先 tmp 副本，失败读 filesDir，再失败默认值） */
    fun loadForUi(appContext: Context): TuneConfig {
        snapshot = readFromAnyChannel(appContext)
        return snapshot
    }

    // ===== 目标进程：免 root 读取 + 热更新 =====

    /**
     * 目标进程调用：加载配置并注册 FileObserver 热更新。
     * @param context 目标进程内任意可用 Context（由 Feature 在 hook 点获取）
     */
    fun loadAndWatch(context: Context, onChange: (TuneConfig) -> Unit) {
        attachFallbackContext(context)
        reload(context)
        listeners.add { onChange(snapshot) }
        if (!observerStarted) {
            observerStarted = true
            // 监听文件所在目录，兼容“写临时文件再 rename（MOVED_TO）”的更新方式
            val obs = object : FileObserver(File(TMP_FILE).parentFile!!,
                CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path != File(TMP_FILE).name) return
                    mainHandler.post {
                        val changed = reload(context)
                        if (changed) listeners.forEach { runCatching { it() } }
                    }
                }
            }
            observer = obs
            obs.startWatching()
            TuneLog.i("config FileObserver watching $TMP_FILE")
        }
    }

    /** 注册配置变更监听（主线程回调）；MainHook 用它向命中 Feature 分发 onConfigChanged */
    fun addChangeListener(l: () -> Unit) {
        listeners.add(l)
    }

    /**
     * 登记 fallback Context：tmp 通道被 SELinux 拒绝时，用它 createPackageContext
     * 读 App filesDir 副本。可在 Feature 拿到 View/Activity Context 后补登记。
     */
    fun attachFallbackContext(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Context 只在 reload 时临时使用，不长期持有，避免 SystemUI/Launcher 生命周期问题
    }

    /** 重新加载；返回配置是否发生变化 */
    private fun reload(context: Context): Boolean {
        val cfg = readFromAnyChannel(context)
        val changed = cfg != snapshot
        if (changed) {
            snapshot = cfg
            TuneLog.i("config loaded: global=${cfg.globalEnabled}, drift=${cfg.driftEnabled}" +
                    "(${cfg.driftPx}px/${cfg.driftIntervalSec}s), rot180=${cfg.rotation180Enabled}" +
                    ", pocket=${cfg.pocketEnabled}(lightGate=${cfg.pocketUseLightGate})")
        }
        return changed
    }

    /**
     * 双通道读取：tmp 副本优先，失败回退 createPackageContext。
     * 任何异常/缺失都安全降级到默认值，绝不让目标进程崩溃。
     */
    private fun readFromAnyChannel(context: Context): TuneConfig {
        // 通道 1：/data/local/tmp 全局可读副本
        try {
            val f = File(TMP_FILE)
            if (f.exists() && f.canRead()) {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                TuneLog.i("config loaded via tmp channel ($TMP_FILE)")
                return parse(props)
            }
        } catch (t: Throwable) {
            // SELinux 拒绝等情况：EACCES 也会走到这里（canRead 可能为 true 但 open 被拒）
            TuneLog.i("tmp config channel unavailable (${t.message}), try packageContext fallback")
        }

        // 通道 2：createPackageContext 读 App filesDir（旧仓已验证方案）
        try {
            val remote = context.createPackageContext(PKG, Context.CONTEXT_IGNORE_SECURITY)
            val f = File(remote.filesDir, FALLBACK_FILE)
            if (f.exists()) {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                TuneLog.i("config loaded via packageContext fallback")
                return parse(props)
            }
        } catch (t: Throwable) {
            TuneLog.i("packageContext fallback unavailable (${t.message}), use defaults")
        }
        return TuneConfig.DEFAULT
    }

    // ===== 编解码 =====

    private fun encode(cfg: TuneConfig): String {
        val props = Properties()
        props[TuneConfig.KEY_GLOBAL_ENABLED] = cfg.globalEnabled.toString()
        props[TuneConfig.KEY_DRIFT_ENABLED] = cfg.driftEnabled.toString()
        props[TuneConfig.KEY_DRIFT_PX] = cfg.driftPx.toString()
        props[TuneConfig.KEY_DRIFT_INTERVAL_SEC] = cfg.driftIntervalSec.toString()
        props[TuneConfig.KEY_ROTATION180_ENABLED] = cfg.rotation180Enabled.toString()
        props[TuneConfig.KEY_POCKET_ENABLED] = cfg.pocketEnabled.toString()
        props[TuneConfig.KEY_POCKET_USE_LIGHT_GATE] = cfg.pocketUseLightGate.toString()
        val bos = ByteArrayOutputStream()
        // Properties.store 只允许 ISO-8859-1，配置值全是 ASCII，无中文问题；注释写英文保险
        props.store(bos, "CyanTune global config")
        return bos.toString("UTF-8")
    }

    private fun parse(props: Properties): TuneConfig {
        val d = TuneConfig.DEFAULT
        return TuneConfig(
            globalEnabled = props.getProperty(TuneConfig.KEY_GLOBAL_ENABLED)?.toBoolean()
                ?: d.globalEnabled,
            driftEnabled = props.getProperty(TuneConfig.KEY_DRIFT_ENABLED)?.toBoolean()
                ?: d.driftEnabled,
            driftPx = props.getProperty(TuneConfig.KEY_DRIFT_PX)?.toIntOrNull() ?: d.driftPx,
            driftIntervalSec = props.getProperty(TuneConfig.KEY_DRIFT_INTERVAL_SEC)?.toIntOrNull()
                ?: d.driftIntervalSec,
            rotation180Enabled =
                props.getProperty(TuneConfig.KEY_ROTATION180_ENABLED)?.toBoolean()
                    ?: d.rotation180Enabled,
            pocketEnabled = props.getProperty(TuneConfig.KEY_POCKET_ENABLED)?.toBoolean()
                ?: d.pocketEnabled,
            pocketUseLightGate =
                props.getProperty(TuneConfig.KEY_POCKET_USE_LIGHT_GATE)?.toBoolean()
                    ?: d.pocketUseLightGate,
        )
    }
}
