package com.utomat.flowlite

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One interface for every speech engine. Input is 16 kHz mono float PCM, already trimmed by VAD. */
interface Engine {
    val name: String
    val model: String
    fun transcribe(segments: List<FloatArray>, language: String): String
    fun close() {}
}

data class ModelFile(val url: String, val name: String, val bytes: Long, val sha256: String)
data class ModelSpec(val id: String, val label: String, val files: List<ModelFile>) {
    val totalBytes get() = files.sumOf { it.bytes }
    fun dir(ctx: Context) = File(ctx.filesDir, "models/$id")
    fun path(ctx: Context, name: String) = File(dir(ctx), name).absolutePath
    fun isReady(ctx: Context) = files.all { File(dir(ctx), it.name).length() == it.bytes }
}

object Models {
    private const val HF_PK = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/"
    private const val HF_WH = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/"

    val VAD = ModelSpec("silero-vad", "Silero VAD", listOf(
        ModelFile("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", "silero_vad.onnx", 643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")))
    val PARAKEET = ModelSpec("parakeet-tdt-0.6b-v3-int8", "Parakeet TDT 0.6B v3", listOf(
        ModelFile(HF_PK + "encoder.int8.onnx", "encoder.int8.onnx", 652_184_281,
            "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        ModelFile(HF_PK + "decoder.int8.onnx", "decoder.int8.onnx", 11_845_275,
            "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        ModelFile(HF_PK + "joiner.int8.onnx", "joiner.int8.onnx", 6_355_277,
            "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        ModelFile(HF_PK + "tokens.txt", "tokens.txt", 93_939,
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")))
    val WHISPER = ModelSpec("whisper-base-int8", "Whisper base", listOf(
        ModelFile(HF_WH + "base-encoder.int8.onnx", "base-encoder.int8.onnx", 29_120_534,
            "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
        ModelFile(HF_WH + "base-decoder.int8.onnx", "base-decoder.int8.onnx", 130_672_026,
            "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
        ModelFile(HF_WH + "base-tokens.txt", "base-tokens.txt", 816_730,
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126")))

    fun forEngine(engine: String) = when (engine) {
        "parakeet" -> listOf(VAD, PARAKEET)
        "whisper" -> listOf(VAD, WHISPER)
        else -> listOf(VAD)
    }

    /** Downloads missing files, resuming partial ones. progress(done, total) in bytes. */
    fun download(ctx: Context, specs: List<ModelSpec>, progress: (Long, Long) -> Unit) {
        val total = specs.sumOf { it.totalBytes }
        var done = 0L
        for (spec in specs) for (f in spec.files) {
            val out = File(spec.dir(ctx), f.name)
            if (out.length() == f.bytes) { done += f.bytes; progress(done, total); continue }
            out.parentFile!!.mkdirs()
            val part = File(out.path + ".part")
            val have = part.length().takeIf { it < f.bytes } ?: 0L.also { part.delete() }
            val c = URL(f.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val append = c.responseCode == 206
            if (c.responseCode !in listOf(200, 206)) throw IOException("HTTP ${c.responseCode} for ${f.name}")
            var got = if (append) have else 0L
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); got += n
                        if (got - lastReport > 1_000_000) { lastReport = got; progress(done + got, total) }
                    }
                }
            }
            if (part.length() != f.bytes) throw IOException("Incomplete download for ${f.name}")
            if (sha256(part) != f.sha256) { part.delete(); throw IOException("Checksum mismatch for ${f.name}, download again") }
            part.renameTo(out)
            done += f.bytes; progress(done, total)
        }
    }
}

internal fun sha256(file: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Silero VAD: drops silence and splits speech into segments of at most 20 s. */
class SpeechTrimmer(ctx: Context) {
    private val vad = Vad(null, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = Models.VAD.path(ctx, "silero_vad.onnx"),
            threshold = 0.4f, minSilenceDuration = 0.6f, minSpeechDuration = 0.2f, maxSpeechDuration = 20f),
        sampleRate = 16000, numThreads = 1))

    fun segments(samples: FloatArray): List<FloatArray> {
        vad.reset()
        val out = mutableListOf<FloatArray>()
        var i = 0
        while (i + 512 <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + 512)); i += 512
            while (!vad.empty()) { out += vad.front().samples; vad.pop() }
        }
        vad.flush()
        while (!vad.empty()) { out += vad.front().samples; vad.pop() }
        return out
    }

    fun close() = vad.release()
}

class SherpaEngine(ctx: Context, private val whisper: Boolean) : Engine {
    override val name = "sherpa-onnx"
    override val model = if (whisper) "whisper-base-int8" else "parakeet-tdt-0.6b-v3-int8"
    private var language = ""
    private val recognizer: OfflineRecognizer = OfflineRecognizer(null, config(ctx, ""))
    private val ctx = ctx.applicationContext

    private fun config(ctx: Context, lang: String): OfflineRecognizerConfig {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val mc = if (whisper) {
            val m = Models.WHISPER
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = m.path(ctx, "base-encoder.int8.onnx"),
                    decoder = m.path(ctx, "base-decoder.int8.onnx"), language = lang, tailPaddings = 1000),
                tokens = m.path(ctx, "base-tokens.txt"), numThreads = threads, modelType = "whisper")
        } else {
            val m = Models.PARAKEET
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = m.path(ctx, "encoder.int8.onnx"),
                    decoder = m.path(ctx, "decoder.int8.onnx"), joiner = m.path(ctx, "joiner.int8.onnx")),
                tokens = m.path(ctx, "tokens.txt"), numThreads = threads, modelType = "nemo_transducer")
        }
        return OfflineRecognizerConfig(featConfig = FeatureConfig(16000, 80), modelConfig = mc)
    }

    override fun transcribe(segments: List<FloatArray>, language: String): String {
        // Parakeet detects the language itself; Whisper takes "" for auto detect.
        val lang = if (language == "auto") "" else language
        if (whisper && lang != this.language) { recognizer.setConfig(config(ctx, lang)); this.language = lang }
        return segments.joinToString(" ") { seg ->
            val s = recognizer.createStream()
            try {
                s.acceptWaveform(seg, 16000)
                recognizer.decode(s)
                recognizer.getResult(s).text.trim()
            } finally { s.release() }
        }.trim()
    }

    override fun close() = recognizer.release()
}

/** Any OpenAI-compatible /audio/transcriptions endpoint. Off by default; audio leaves the device. */
class CloudEngine(private val url: String, private val key: String, override val model: String) : Engine {
    override val name = "cloud"

    override fun transcribe(segments: List<FloatArray>, language: String): String {
        val wav = wav(segments)
        val boundary = "flowlite" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            fun field(n: String, v: String) = write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
            field("model", model)
            if (language != "auto") field("language", language)
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            write(wav); write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val c = URL(url.trimEnd('/').let { if (it.endsWith("/audio/transcriptions")) it else "$it/audio/transcriptions" })
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { it.write(body) }
        if (c.responseCode != 200) throw IOException("Cloud engine HTTP ${c.responseCode}: " + c.errorStream?.bufferedReader()?.readText())
        return JSONObject(c.inputStream.bufferedReader().readText()).optString("text").trim()
    }

    private fun wav(segments: List<FloatArray>): ByteArray {
        val n = segments.sumOf { it.size }
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (s in segments) for (x in s) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }
}
