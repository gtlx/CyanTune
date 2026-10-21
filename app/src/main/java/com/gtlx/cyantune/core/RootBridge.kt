package com.gtlx.cyantune.core

import java.io.DataOutputStream

/**
 * 按需 root 能力封装（无常驻守护）。
 *
 * 设计原则：核心功能尽量零 root；root 只用于增强（写全局配置、强制熄屏、重启系统进程）。
 * 每次需要时现开一次 su，执行完即关闭，简单省电。
 */
object RootBridge {

    val isRootAvailable: Boolean by lazy { probeRoot() }

    private fun probeRoot(): Boolean = try {
        exec("id").startsWith("uid=0")
    } catch (_: Throwable) {
        false
    }

    /** 以 root 执行单条 shell 命令，返回 stdout；失败抛异常 */
    fun exec(command: String, timeoutMs: Long = 8000): String {
        val process = ProcessBuilder("su").redirectErrorStream(false).start()
        val out = StringBuilder()
        val err = StringBuilder()

        DataOutputStream(process.outputStream).use { os ->
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
        }

        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
            } catch (_: Throwable) {}
        }
        val errReader = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
            } catch (_: Throwable) {}
        }
        reader.start(); errReader.start()

        val finished = process.waitFor(
            timeoutMs,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("su command timed out: $command")
        }
        reader.join(1000); errReader.join(1000)
        val code = process.exitValue()
        if (code != 0) {
            throw RuntimeException("su exit=$code: ${err.trim()}")
        }
        return out.toString().trim()
    }

    /** 以 root 执行一段 sh 脚本（可多行） */
    fun execScript(script: String, timeoutMs: Long = 8000) = exec(script, timeoutMs)
}
