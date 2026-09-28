package org.itantra.app

import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.delay

/** Samples this process' CPU usage percentage over [intervalMs] by reading /proc/self/stat twice. */
object CpuSampler {
    suspend fun samplePercent(intervalMs: Long = 1000L): Float? {
        val ticksPerSec = try {
            Os.sysconf(OsConstants._SC_CLK_TCK)
        } catch (t: Throwable) {
            return null
        }
        val before = readUtimeStime() ?: return null
        delay(intervalMs)
        val after = readUtimeStime() ?: return null
        val deltaTicks = after - before
        val elapsedTicks = (intervalMs / 1000.0) * ticksPerSec
        if (elapsedTicks <= 0) return null
        return (deltaTicks / elapsedTicks * 100.0).toFloat().coerceIn(0f, 100f * Runtime.getRuntime().availableProcessors())
    }

    private fun readUtimeStime(): Long? = try {
        val text = File("/proc/self/stat").readText()
        // Fields after the (comm) in parentheses can contain spaces/parens, so split after the last ')'.
        val afterComm = text.substringAfterLast(')').trim().split(' ')
        // afterComm[0] = state(3rd field); utime=14th field overall = afterComm[11], stime=15th = afterComm[12]
        val utime = afterComm[11].toLong()
        val stime = afterComm[12].toLong()
        utime + stime
    } catch (t: Throwable) {
        null
    }
}
