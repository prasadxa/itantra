package org.itantra.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object PermissionUtil {
    /** RECORD_AUDIO + POST_NOTIFICATIONS(33+) + ACCESS_FINE/COARSE_LOCATION (SOS) + whatever
     * :transport needs for this SDK level. Location is requested up front alongside everything
     * else so [org.itantra.app.ui.sos.SosSheet] never has to run its own permission flow; a phone
     * that denies it still gets a location-less SOS (see [org.itantra.app.location.LocationProvider]). */
    fun required(sdkInt: Int): Array<String> {
        val perms = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
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
