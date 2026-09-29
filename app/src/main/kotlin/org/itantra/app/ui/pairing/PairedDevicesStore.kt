package org.itantra.app.ui.pairing

import android.content.Context
import org.itantra.transport.crypto.PairingInfo

/** One accepted peer, as shown in the "paired devices" list. */
data class PairedDevice(val info: PairingInfo, val pairedAtMs: Long)

/**
 * Persists accepted peers' [PairingInfo] (device id + both *public* keys only — nothing secret,
 * see [PairingInfo]) as a `Set<String>` of [PairingInfo.toQrString] values, one SharedPreferences
 * entry per install. Independent of [DeviceIdentityHolder] (that's *this* device's own identity).
 */
object PairedDevicesStore {
    private const val PREFS_NAME = "itantra_paired_devices"
    private const val KEY_ENTRIES = "entries" // "<pairedAtMs>|<qrString>", one per peer

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<PairedDevice> =
        prefs(context).getStringSet(KEY_ENTRIES, emptySet()).orEmpty()
            .mapNotNull { raw ->
                val sep = raw.indexOf('|')
                if (sep < 0) return@mapNotNull null
                val pairedAt = raw.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val info = runCatching { PairingInfo.fromQrString(raw.substring(sep + 1)) }.getOrNull() ?: return@mapNotNull null
                PairedDevice(info, pairedAt)
            }
            .sortedByDescending { it.pairedAtMs }

    /** Adds/replaces [info] (re-pairing the same device id updates its keys + timestamp). */
    fun add(context: Context, info: PairingInfo) {
        val existing = list(context).filter { it.info.deviceId != info.deviceId }
        val updated = existing + PairedDevice(info, System.currentTimeMillis())
        save(context, updated)
    }

    fun remove(context: Context, deviceId: String) {
        save(context, list(context).filter { it.info.deviceId != deviceId })
    }

    private fun save(context: Context, devices: List<PairedDevice>) {
        val encoded = devices.map { "${it.pairedAtMs}|${it.info.toQrString()}" }.toSet()
        prefs(context).edit().putStringSet(KEY_ENTRIES, encoded).apply()
    }
}
