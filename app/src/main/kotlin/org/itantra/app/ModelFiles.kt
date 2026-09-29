package org.itantra.app

import java.io.File
import org.itantra.core.Lang
import org.itantra.core.ModelPaths

data class ModelFileEntry(val relativePath: String, val file: File) {
    val present: Boolean get() = file.exists() && file.isFile
    val sizeBytes: Long get() = if (present) file.length() else 0L
}

/** Expected on-device model files, per the layout documented in [ModelPaths] / docs/design.md. */
object ExpectedModelFiles {
    fun list(paths: ModelPaths): List<ModelFileEntry> {
        val entries = mutableListOf<ModelFileEntry>()
        entries += ModelFileEntry("vad/silero_vad.onnx", paths.vad)
        for (name in listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.vocab")) {
            entries += ModelFileEntry("stt/sravaani/$name", File(paths.sttDir, name))
        }
        for (name in listOf("indic-mio-q8_0.gguf", "miocodec.gguf")) {
            entries += ModelFileEntry("tts/mio/$name", File(paths.mioDir, name))
        }
        // Optional: smaller/faster weights for Profile.LITE (see ProfileManager); not required.
        entries += ModelFileEntry("tts/mio/indic-mio-q4.gguf", paths.mioModelLite)
        // Optional: F16 MioCodec (~half of miocodec.gguf's size), preferred when present — see
        // MioTtsEngine and tools/tts/convert_miocodec_f16.py.
        entries += ModelFileEntry("tts/mio/miocodec-f16.gguf", File(paths.mioDir, "miocodec-f16.gguf"))
        // wavlm.gguf is no longer loaded by the app at all (see MioNative's class doc) — listed
        // only so a stale copy on device shows up as unused disk space, not as "missing".
        entries += ModelFileEntry("tts/mio/wavlm.gguf (unused)", File(paths.mioDir, "wavlm.gguf"))
        for (lang in Lang.entries) {
            entries += ModelFileEntry("tts/mio/voices/${lang.code}.emb.gguf", paths.mioVoice(lang))
        }
        for (name in listOf("model.onnx", "tokens.txt")) {
            entries += ModelFileEntry("tts/vits-rasa/$name", File(paths.vitsDir, name))
        }
        return entries
    }
}
