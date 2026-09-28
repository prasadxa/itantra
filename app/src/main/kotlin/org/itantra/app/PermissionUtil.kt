package org.itantra.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object PermissionUtil {
    /** RECORD_AUDIO + POST_NOTIFICATIONS(33+) + whatever :transport needs for this SDK level. */
    fun required(sdkInt: Int): Array<String> {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (sdkInt >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
        perms += org.itantra.transport.TransportPermissions.required(sdkInt)
        return perms.distinct().toTypedArray()
    }

    fun isIgnoringBatteryOptimizations(activity: Activity): Boolean {
        val pm = activity.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (isIgnoringBatteryOptimizations(activity)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        activity.startActivity(intent)
    }
}
