package org.itantra.core

import java.io.File

/**
 * On-device model layout under `<externalFilesDir>/models` (pushed via adb or imported; never in the APK):
 * ```
 * vad/silero_vad.onnx
 * stt/sravaani/{encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt, bpe.vocab}
 * tts/mio/{indic-mio-q8_0.gguf, miocodec.gguf, wavlm.gguf, voices/<lang>.emb.gguf}
 * tts/vits-rasa/{model.onnx, tokens.txt}
 * ```
 */
class ModelPaths(val root: File) {
    val vad get() = File(root, "vad/silero_vad.onnx")
    val sttDir get() = File(root, "stt/sravaani")
    val mioDir get() = File(root, "tts/mio")
    val vitsDir get() = File(root, "tts/vits-rasa")
    fun mioVoice(lang: Lang) = File(mioDir, "voices/${lang.code}.emb.gguf")
}
