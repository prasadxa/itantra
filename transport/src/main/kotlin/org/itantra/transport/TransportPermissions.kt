package org.itantra.transport

import android.Manifest
import android.os.Build

/** Runtime permissions [WifiTransport]/[BleTransport] need, by API level. */
object TransportPermissions {
    fun required(sdkInt: Int): List<String> {
        val perms = mutableListOf<String>()
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) { // 33+: Wi-Fi NSD discovery/advertise
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (sdkInt >= Build.VERSION_CODES.S) { // 31+: modern BLE runtime permissions
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (sdkInt <= 32) { // pre-33 Wi-Fi discovery and pre-31 BLE scan both need location
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms
    }
}
