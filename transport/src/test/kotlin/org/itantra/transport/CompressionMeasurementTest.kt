package org.itantra.transport

import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.VoiceMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Measures real wire sizes (JSON vs raw binary vs binary+dictionary-compressed) on the FLEURS
 * transcripts this project already has locally (`models/eval/stt/<lang>/manifest.tsv`, 30
 * sentences/language, plus `models/testclips/clips.tsv`), exercising the exact runtime code path
 * ([FrameCodec]/[BinaryFrameCodec]/[TextDictionaryCodec]) rather than the Python generator's
 * estimate. Asserts the target from the task: binary+compressed median <= 80 bytes.
 *
 * Prints a per-language table to stdout (`./gradlew :transport:testDebugUnitTest --tests
 * "*CompressionMeasurementTest" -i` to see it) - the numbers quoted in transport/README.md and the
 * task report come from this test's output.
 */
class CompressionMeasurementTest {

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "models/testclips/clips.tsv").exists()) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    private fun readManifest(root: File, lang: String): List<String> {
        val f = File(root, "models/eval/stt/$lang/manifest.tsv")
        if (!f.exists()) return emptyList()
        val lines = f.readLines().drop(1) // header
        return lines.mapNotNull { line ->
            val cols = line.split("\t")
            if (cols.size >= 3) cols[2].trim().takeIf { it.isNotEmpty() } else null
        }
    }

    private fun readClips(root: File): Map<String, String> {
        val f = File(root, "models/testclips/clips.tsv")
        if (!f.exists()) return emptyMap()
        return f.readLines().mapNotNull { line ->
            val cols = line.split("\t")
            if (cols.size >= 3) cols[1].trim() to cols[2].trim() else null
        }.toMap()
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun varintLen(v: Int): Int {
        var n = v
        var len = 1
        while (n >= 0x80) {
            n = n ushr 7
            len++
        }
        return len
    }

    /** Same 16-byte header + scheme byte + varint length as [BinaryFrameCodec], but forcing the
     * text payload to raw UTF-8 (no dictionary compression) - i.e. "binary framing alone, no text
     * compression", for the comparison table. */
    private fun rawBinarySize(text: String): Int {
        val rawLen = text.toByteArray(Charsets.UTF_8).size
        return 16 + 1 + varintLen(rawLen) + rawLen
    }

    @Test
    fun `binary plus dictionary compression median is at most 80 bytes per sentence`() {
        val root = findRepoRoot()
        val clips = readClips(root)
        val langs = Lang.entries

        val jsonSizes = mutableListOf<Int>()
        val binaryRawSizes = mutableListOf<Int>()
        val binaryCompressedSizes = mutableListOf<Int>()
        val perLang = StringBuilder()
        perLang.append(String.format("%-4s %6s %8s %8s %8s%n", "lang", "n", "json", "bin", "bin+dict"))

        var totalSentences = 0
        for (lang in langs) {
            val sentences = readManifest(root, lang.code).toMutableList()
            clips[lang.code]?.let { sentences.add(it) }
            if (sentences.isEmpty()) continue

            val langJson = mutableListOf<Int>()
            val langBinRaw = mutableListOf<Int>()
            val langBinCompressed = mutableListOf<Int>()
            for (text in sentences) {
                val msg = VoiceMessage(
                    id = UUID.randomUUID().toString(), from = "device-eval-01", lang = lang,
                    text = text, priority = Priority.NORMAL, emotion = null, sentAt = System.currentTimeMillis(),
                )
                val frame = Frame.Msg(msg)

                val json = FrameCodec.encode(frame).size
                val binary = BinaryFrameCodec.encode(frame).size
                val binaryRaw = rawBinarySize(text)

                jsonSizes.add(json)
                binaryCompressedSizes.add(binary)
                binaryRawSizes.add(binaryRaw)
                langJson.add(json)
                langBinRaw.add(binaryRaw)
                langBinCompressed.add(binary)
            }
            totalSentences += sentences.size
            perLang.append(
                String.format(
                    "%-4s %6d %8d %8d %8d%n",
                    lang.code, sentences.size, median(langJson), median(langBinRaw), median(langBinCompressed),
                ),
            )
        }

        println("=== Compression measurement (FLEURS manifests + clips.tsv, $totalSentences sentences) ===")
        print(perLang)
        println(
            "overall median bytes/sentence: json=${median(jsonSizes)}B  binary(raw text)=${median(binaryRawSizes)}B  " +
                "binary+dict-compression=${median(binaryCompressedSizes)}B",
        )

        assertTrue("need FLEURS sentences to measure (repo root not found?)", totalSentences > 0)
        assertTrue(
            "binary+compressed median (${median(binaryCompressedSizes)}B) should be <= 80B",
            median(binaryCompressedSizes) <= 80,
        )
        assertTrue(
            "binary median should be smaller than JSON median",
            median(binaryCompressedSizes) < median(jsonSizes),
        )
    }
}
