package com.gtlx.cyantune.core

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 统一日志：同时打 logcat 和 Xposed 框架日志（Vector/LSPosed 可在框架日志里看到）。
 */
object TuneLog {
    const val TAG = "CyanTune"

    fun d(msg: String) {
        Log.d(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        runCatching { XposedBridge.log("[$TAG] $msg: $t") }
    }
}
