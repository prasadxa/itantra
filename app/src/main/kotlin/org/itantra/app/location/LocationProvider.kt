package org.itantra.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** One GPS/GNSS fix. [accuracyM] is -1f when the platform reported no accuracy for this fix. */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timestampMs: Long,
    val constellations: Set<String> = emptySet(),
) {
    val accuracyOrNull: Float? get() = accuracyM.takeIf { it >= 0f }
}

/**
 * GPS/GNSS-only location via the framework [LocationManager] — deliberately **not**
 * FusedLocationProviderClient/Play Services (this app targets devices/regions where Play Services
 * may be unavailable; see docs/sih-idea-pack.html). Reports which GNSS constellations are actually
 * in use for the current fix via [GnssStatus], including `"NavIC"` for ISRO's IRNSS constellation.
 */
class LocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _constellationsInUse = MutableStateFlow<Set<String>>(emptySet())
    /** Updated continuously while [startGnssStatusUpdates] is active. */
    val constellationsInUse: StateFlow<Set<String>> = _constellationsInUse

    private var gnssCallback: GnssStatus.Callback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Best of whatever's cached by the platform right now — instant, no fix requested. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): LocationFix? {
        val manager = lm ?: return null
        if (!hasPermission()) return null
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val loc = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best?.toFix(_constellationsInUse.value)
    }

    /**
     * Requests one fresh GPS fix, waiting up to [timeoutMs]. Falls back to [lastKnown] if the GPS
     * provider is disabled, permission is missing, or the timeout elapses before a fix arrives.
     */
    @SuppressLint("MissingPermission")
    suspend fun getFix(timeoutMs: Long = 8_000L): LocationFix? {
        val manager = lm ?: return null
        if (!hasPermission()) return null
        if (!runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) return lastKnown()

        val fresh = suspendCancellableCoroutine<Location?> { cont ->
            var resolved = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (resolved) return
                    resolved = true
                    runCatching { manager.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }
            }
            val timeoutRunnable = Runnable {
                if (resolved) return@Runnable
                resolved = true
                runCatching { manager.removeUpdates(listener) }
                if (cont.isActive) cont.resume(null)
            }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)
            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(timeoutRunnable)
                runCatching { manager.removeUpdates(listener) }
            }
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                resolved = true
                mainHandler.removeCallbacks(timeoutRunnable)
                if (cont.isActive) cont.resume(null)
            }
        }
        return fresh?.toFix(_constellationsInUse.value) ?: lastKnown()
    }

    /** Starts observing which GNSS constellations are used for the current fix (for the SOS
     * sheet's "NavIC / GPS / …" chips). No-op without permission or below the GNSS API. */
    @SuppressLint("MissingPermission")
    fun startGnssStatusUpdates() {
        val manager = lm ?: return
        if (!hasPermission() || gnssCallback != null) return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val used = mutableSetOf<String>()
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used += constellationLabel(status.getConstellationType(i))
                }
                _constellationsInUse.value = used
            }
        }
        gnssCallback = callback
        runCatching { manager.registerGnssStatusCallback(callback, mainHandler) }
    }

    fun stopGnssStatusUpdates() {
        val callback = gnssCallback ?: return
        runCatching { lm?.unregisterGnssStatusCallback(callback) }
        gnssCallback = null
        _constellationsInUse.value = emptySet()
    }

    private fun Location.toFix(constellations: Set<String>) =
        LocationFix(latitude, longitude, if (hasAccuracy()) accuracy else -1f, time, constellations)

    companion object {
        /** ISRO's regional NavIC constellation reports as [GnssStatus.CONSTELLATION_IRNSS]. */
        fun constellationLabel(type: Int): String = when (type) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
            else -> "GNSS"
        }
    }
}
