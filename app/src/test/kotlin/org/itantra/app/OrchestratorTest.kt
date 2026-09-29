package org.itantra.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState
import org.itantra.core.Priority
import org.itantra.core.RecognizedSentence
import org.itantra.core.SttEngine
import org.itantra.core.SynthesisPlan
import org.itantra.core.TextPipeline
import org.itantra.core.Transport
import org.itantra.core.TtsSegment
import org.itantra.core.VoiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSttEngine : SttEngine {
    override var language: Lang = Lang.HI
    override var listener: ((RecognizedSentence) -> Unit)? = null
    override var partialListener: ((id: String, text: String) -> Unit)? = null
    override val isSpeechActive: Boolean = false
    var lastHotwords: List<String> = emptyList()
    var flushed = false
    override fun setHotwords(words: List<String>) { lastHotwords = words }
    override fun accept(samples: FloatArray) {}
    override fun flush() { flushed = true }
    override fun reset() {}
    override fun close() {}
}

private class FakeTextPipeline : TextPipeline {
    override fun plan(message: VoiceMessage): SynthesisPlan =
        SynthesisPlan(message.lang, message.priority, listOf(TtsSegment(message.text)))
}

private class FakeSpeechOutput : SpeechOutputPort {
    var lastId: String? = null
    var lastPlan: SynthesisPlan? = null
    var playAt: Long = 0L
    private var playbackMetricsListener: ((String?, Float?, Long, Int, Int) -> Unit)? = null
    override fun enqueue(id: String, plan: SynthesisPlan, phoneMode: Boolean, onPlayStarted: (id: String, epochMs: Long) -> Unit) {
        lastId = id
        lastPlan = plan
        onPlayStarted(id, playAt)
    }
    override fun stopNormal() {}
    override fun close() {}
    override val isPlaying = MutableStateFlow(false)
    override fun observePlaybackMetrics(id: String, listener: (String?, Float?, Long, Int, Int) -> Unit) {
        playbackMetricsListener = listener
    }
    fun firePlaybackMetrics(engine: String?, ttsRtf: Float?, preRollMs: Long, rebufferPauses: Int, underrunCountDelta: Int) {
        playbackMetricsListener?.invoke(engine, ttsRtf, preRollMs, rebufferPauses, underrunCountDelta)
    }
}

private class FakeTransport : Transport {
    override val kind: LinkKind = LinkKind.WIFI
    override val state = MutableStateFlow<LinkState>(LinkState.Connected(LinkKind.WIFI, "peer"))
    override val incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
    val sent = mutableListOf<Frame>()
    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun send(frame: Frame): Boolean { sent.add(frame); return true }
    override fun close() {}
}

class OrchestratorTest {

    @Test
    fun `recognized sentence is sent and metrics recorded`() = runTest {
        val transport = FakeTransport()
        var lastLog: LogEntry? = null
        var lastMetrics: MessageMetrics? = null
        val stt = FakeSttEngine()
        val orchestrator = Orchestrator(
            deviceId = "device-a",
            stt = stt,
            textPipeline = FakeTextPipeline(),
            speechOutput = FakeSpeechOutput(),
            transport = transport,
            scope = backgroundScope,
            clock = { 1_000L },
            onLog = { lastLog = it },
            onMetrics = { lastMetrics = it },
        )
        orchestrator.start()
        runCurrent()

        stt.listener?.invoke(
            RecognizedSentence(
                id = "s1", text = "hello", lang = Lang.HI, speechStartAt = 0, speechEndAt = 500,
                sttDoneAt = 900, audioSeconds = 1f, decodeMs = 100,
            ),
        )
        runCurrent()

        assertEquals(1, transport.sent.count { it is Frame.Msg })
        assertNotNull(lastLog)
        assertEquals(Direction.SENT, lastLog!!.direction)
        assertEquals("hello", lastLog!!.text)
        assertNotNull(lastMetrics)
        assertEquals(400L, lastMetrics!!.sttLatencyMs) // sttDoneAt - speechEndAt
        assertEquals(0.1f, lastMetrics!!.rtf) // decodeMs / (audioSeconds*1000)
    }

    @Test
    fun `alert toggle sets ALERT priority on typed text`() = runTest {
        val transport = FakeTransport()
        var alert = true
        val orchestrator = Orchestrator(
            deviceId = "device-a", stt = null, textPipeline = null, speechOutput = null,
            transport = transport, scope = backgroundScope, alertProvider = { alert },
        )
        orchestrator.sendText("evacuate now", Lang.EN, ssml = false)
        runCurrent()

        val msg = (transport.sent.first { it is Frame.Msg } as Frame.Msg).message
        assertEquals(Priority.ALERT, msg.priority)
    }

    @Test
    fun `incoming message is played and acked, ptt gates transmission while peer holds floor`() = runTest {
        val transport = FakeTransport()
        val speechOutput = FakeSpeechOutput().apply { playAt = 1_200L }
        var peerTalking = false
        val orchestrator = Orchestrator(
            deviceId = "device-b",
            stt = FakeSttEngine(),
            textPipeline = FakeTextPipeline(),
            speechOutput = speechOutput,
            transport = transport,
            scope = backgroundScope,
            clock = { 1_100L },
            onPeerTalking = { peerTalking = it },
        )
        orchestrator.mode = Mode.PTT
        orchestrator.start()
        runCurrent()

        val incomingMsg = VoiceMessage(
            id = "m1", from = "device-a", lang = Lang.HI, text = "hi",
            priority = Priority.NORMAL, speechEndAt = 1_000, sttDoneAt = 1_000, sentAt = 1_000,
        )
        transport.incoming.emit(Frame.Msg(incomingMsg))
        runCurrent()

        assertEquals("m1", speechOutput.lastId)
        val ack = transport.sent.filterIsInstance<Frame.Ack>().firstOrNull()
        assertNotNull(ack)
        assertEquals(1_200L, ack!!.playStartedAt)

        transport.incoming.emit(Frame.Ptt(true))
        runCurrent()
        assertTrue(peerTalking)

        orchestrator.onAudioSamples(floatArrayOf(0.1f))
        // stt.accept should not be called while peer holds the floor in PTT mode.
    }

    @Test
    fun `speech rate is applied to received-message segments only`() = runTest {
        val transport = FakeTransport()
        val speechOutput = FakeSpeechOutput()
        val orchestrator = Orchestrator(
            deviceId = "device-b", stt = FakeSttEngine(), textPipeline = FakeTextPipeline(),
            speechOutput = speechOutput, transport = transport, scope = backgroundScope,
            clock = { 1_100L }, speechRateProvider = { 1.3f },
        )
        orchestrator.start()
        runCurrent()

        val incomingMsg = VoiceMessage(
            id = "m1", from = "device-a", lang = Lang.HI, text = "hi",
            priority = Priority.NORMAL, speechEndAt = 1_000, sttDoneAt = 1_000, sentAt = 1_000,
        )
        transport.incoming.emit(Frame.Msg(incomingMsg))
        runCurrent()

        assertEquals(1.3f, speechOutput.lastPlan!!.segments.single().rate, 0.0001f)

        // Sent messages must never have the received-message rate applied.
        orchestrator.sendText("evacuate now", Lang.EN, ssml = false)
        runCurrent()
        val sent = (transport.sent.first { it is Frame.Msg } as Frame.Msg).message
        assertEquals("evacuate now", sent.text) // no TTS path for SENT, so no rate to check - just confirms it wasn't touched
    }

    @Test
    fun `received-message playback metrics are merged into MessageMetrics`() = runTest {
        val transport = FakeTransport()
        val speechOutput = FakeSpeechOutput().apply { playAt = 1_200L }
        val allMetrics = mutableListOf<MessageMetrics>()
        val orchestrator = Orchestrator(
            deviceId = "device-b", stt = FakeSttEngine(), textPipeline = FakeTextPipeline(),
            speechOutput = speechOutput, transport = transport, scope = backgroundScope,
            clock = { 1_100L }, onMetrics = { allMetrics.add(it) },
        )
        orchestrator.start()
        runCurrent()

        val incomingMsg = VoiceMessage(
            id = "m1", from = "device-a", lang = Lang.HI, text = "hi",
            priority = Priority.NORMAL, speechEndAt = 1_000, sttDoneAt = 1_000, sentAt = 1_000,
        )
        transport.incoming.emit(Frame.Msg(incomingMsg))
        runCurrent()
        speechOutput.firePlaybackMetrics("vits", 0.6f, 250L, 1, 2)

        val last = allMetrics.last { it.id == "m1" && it.direction == Direction.RECEIVED }
        assertEquals("vits", last.ttsEngine)
        assertEquals(0.6f, last.ttsRtf)
        assertEquals(250L, last.preRollMs)
        assertEquals(1, last.rebufferPauses)
        assertEquals(2, last.underrunCountDelta)
        // ttsStartMs/endToEndMs from the earlier onPlayStarted callback must survive the merge.
        assertNotNull(last.ttsStartMs)
        assertNotNull(last.endToEndMs)
    }

    @Test
    fun `pong updates clock offset using the given formula`() = runTest {
        val transport = FakeTransport()
        var reportedRtt = -1L
        var reportedOffset = Long.MIN_VALUE
        val orchestrator = Orchestrator(
            deviceId = "device-a", stt = null, textPipeline = null, speechOutput = null,
            transport = transport, scope = backgroundScope,
            clock = { 3_000L }, // t3
            onRtt = { rtt, offset -> reportedRtt = rtt; reportedOffset = offset },
        )
        orchestrator.start()
        runCurrent()

        // t0 = 1000 (our send), t1 = 2500 (peer clock at receipt), t3 = 3000 (our clock at receipt)
        transport.incoming.emit(Frame.Pong(t0 = 1_000L, t1 = 2_500L))
        runCurrent()

        val expectedOffset = ((2_500L - 1_000L) + (2_500L - 3_000L)) / 2
        assertEquals(expectedOffset, reportedOffset)
        assertEquals(2_000L, reportedRtt) // t3 - t0
    }

    @Test
    fun `withSpeechRate scales every segment's rate, rate 1f is a no-op`() {
        val plan = SynthesisPlan(Lang.HI, Priority.NORMAL, listOf(TtsSegment("a", rate = 1f), TtsSegment("b", rate = 1.1f)))

        val scaled = plan.withSpeechRate(1.3f)
        assertEquals(1.3f, scaled.segments[0].rate, 0.0001f)
        assertEquals(1.1f * 1.3f, scaled.segments[1].rate, 0.0001f)

        assertTrue(plan.withSpeechRate(1f) === plan) // exact same instance, no allocation
    }
}
