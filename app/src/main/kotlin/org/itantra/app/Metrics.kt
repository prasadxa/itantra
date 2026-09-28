package org.itantra.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import org.json.JSONObject

/**
 * Structured metrics for automated on-device testing: one JSON object per logcat line, tag
 * [TAG]. Used by engine-load timing (see [EngineFactory]) and the debug broadcast hooks
 * (`app/src/debug/kotlin/org/itantra/app/DebugCommandReceiver.kt`, debug builds only).
 */
object Metrics {
    const val TAG = "ITANTRA_METRIC"

    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun log(vararg fields: Pair<String, Any?>) {
        val json = JSONObject()
        for ((k, v) in fields) {
            json.put(k, v ?: JSONObject.NULL)
        }
        Log.i(TAG, json.toString())
    }
}
