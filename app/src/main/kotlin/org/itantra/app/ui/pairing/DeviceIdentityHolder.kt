package org.itantra.app.ui.pairing

import android.content.Context
import android.provider.Settings
import org.itantra.transport.crypto.DeviceIdentity

/**
 * This device's [DeviceIdentity] for the pairing screen, kept alive for the process's lifetime.
 *
 * [DeviceIdentity] (`:transport`) intentionally exposes no way to read back its raw private-key
 * bytes — "persist the raw bytes; that's an app-layer concern" per its class doc assumes an API
 * this pass doesn't have without editing `:transport/crypto` (out of scope here — read-only per
 * the task split). So a fresh key pair is generated once per app process and reused for every
 * screen visit within that process (stable while the app is open/backgrounded, but rotates on a
 * full app restart) rather than regenerated per composition. [deviceId] instead uses the same
 * stable Android ID [TalkService][org.itantra.app.TalkService] already uses, so a peer's saved
 * device id keeps matching across restarts even though the keys themselves don't.
 */
object DeviceIdentityHolder {
    val identity: DeviceIdentity by lazy { DeviceIdentity.generate() }

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "itantra-device"
}
