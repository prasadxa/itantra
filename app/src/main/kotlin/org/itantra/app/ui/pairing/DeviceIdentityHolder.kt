package org.itantra.app.ui.pairing

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.itantra.transport.crypto.DeviceIdentity

/**
 * This device's [DeviceIdentity] for the pairing screen, loaded once per process and persisted
 * across app restarts (see [IdentityKeyStore]) so a peer's saved QR/pairing info keeps matching
 * this device instead of the public keys rotating on every restart. [deviceId] uses the same
 * stable Android ID [TalkService][org.itantra.app.TalkService] already uses.
 */
object DeviceIdentityHolder {
    @Volatile private var cached: DeviceIdentity? = null

    /** This device's identity: loaded from encrypted storage if present, otherwise generated once
     * and persisted for next time. Stable for the lifetime of the install. */
    fun identity(context: Context): DeviceIdentity {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appContext = context.applicationContext
            val identity = IdentityKeyStore.load(appContext)
                ?: DeviceIdentity.generate().also { IdentityKeyStore.save(appContext, it) }
            cached = identity
            return identity
        }
    }

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "itantra-device"
}

/**
 * Persists [DeviceIdentity.export]'s raw private-key bytes to app-private storage, encrypted with
 * an AES-256-GCM key held in the Android Keystore (hardware-backed where the device supports it;
 * the wrapping key itself is never exported or written to disk - only the Keystore alias is
 * needed to use it again). This is what lets [DeviceIdentityHolder.identity] survive a full app
 * restart instead of regenerating (and so changing the QR's public keys) every process death.
 *
 * Best-effort: any failure (Keystore unavailable, corrupt/undecryptable blob e.g. after a factory
 * reset invalidated the Keystore key) is swallowed and treated as "nothing persisted yet" -
 * [DeviceIdentityHolder] falls back to generating a fresh identity in that case.
 */
private object IdentityKeyStore {
    private const val KEYSTORE_ALIAS = "itantra_device_identity_wrap"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FILE_NAME = "device_identity.bin"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    fun load(context: Context): DeviceIdentity? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            require(blob.size > GCM_IV_BYTES) { "corrupt identity blob" }
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            DeviceIdentity.restore(cipher.doFinal(ciphertext))
        } catch (t: Throwable) {
            null
        }
    }

    fun save(context: Context, identity: DeviceIdentity) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val ciphertext = cipher.doFinal(identity.export())
            File(context.filesDir, FILE_NAME).writeBytes(cipher.iv + ciphertext)
        } catch (t: Throwable) {
            // Identity still works for this process; just won't survive a restart.
        }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
