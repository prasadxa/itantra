package org.itantra.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.itantra.core.Lang
import org.itantra.core.LinkState

/**
 * Process-wide singleton holding UI state, updated by [TalkService]/[Orchestrator] and observed by
 * Compose screens. Chosen over a bound service for simplicity — service and UI run in the same process.
 */
object AppRepository {
    val serviceRunning = MutableStateFlow(false)
    val linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    val mode = MutableStateFlow(Mode.PTT)
    val language = MutableStateFlow(Lang.HI)
    val alertNext = MutableStateFlow(false)
    val peerTalking = MutableStateFlow(false)
    val pttHeld = MutableStateFlow(false)
    val rttMs = MutableStateFlow<Long?>(null)
    val clockOffsetMs = MutableStateFlow(0L)
    val messages = MutableStateFlow<List<LogEntry>>(emptyList())
    val partials = MutableStateFlow<Map<String, PartialEntry>>(emptyMap())
    val metrics = MutableStateFlow<List<MessageMetrics>>(emptyList())
    val engineStatus = MutableStateFlow<EngineStatus?>(null)
    /** id of the message currently audible through TTS playback, for the "speaking…" bubble indicator. */
    val speakingId = MutableStateFlow<String?>(null)
    /** True while TTS is playing (+ a short tail); mic input is gated during this to avoid self-transcription. */
    val ttsPlaying = MutableStateFlow(false)
    /** RMS of the most recent mic chunk in [0,1], for the hold-to-talk pulsing ring. */
    val micLevel = MutableStateFlow(0f)

    fun addLog(entry: LogEntry) {
        messages.update { (it + entry).takeLast(200) }
        partials.update { it - entry.id }
    }

    fun updatePartial(p: PartialEntry) {
        partials.update { it + (p.id to p) }
    }

    fun addOrUpdateMetrics(m: MessageMetrics) {
        metrics.update { list ->
            val idx = list.indexOfFirst { it.id == m.id && it.direction == m.direction }
            if (idx >= 0) list.toMutableList().apply { this[idx] = m } else (list + m).takeLast(200)
        }
    }

    /** Test/service-restart hook. */
    fun reset() {
        messages.value = emptyList()
        partials.value = emptyMap()
        metrics.value = emptyList()
        speakingId.value = null
        ttsPlaying.value = false
    }
}
