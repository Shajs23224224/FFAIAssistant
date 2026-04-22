package com.ffai.assistant.utils

import android.util.Log
import com.ffai.assistant.config.Constants

/**
 * Logger centralizado con niveles y tag consistente.
 */
object Logger {
    
    var debugMode = false
    
    fun d(message: String) {
        if (debugMode) {
            Log.d(Constants.DEBUG_TAG, message)
        }
    }
    
    fun i(message: String) {
        Log.i(Constants.DEBUG_TAG, message)
    }
    
    fun w(message: String) {
        Log.w(Constants.DEBUG_TAG, message)
    }

    fun w(message: String, throwable: Throwable) {
        Log.w(Constants.DEBUG_TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(Constants.DEBUG_TAG, message, throwable)
    }
    
    fun wtf(message: String, throwable: Throwable? = null) {
        Log.wtf(Constants.DEBUG_TAG, message, throwable)
    }
    
    /**
     * Log de performance con timestamp.
     */
    fun perf(operation: String, durationMs: Long) {
        d("[PERF] $operation: ${durationMs}ms")
    }
}
