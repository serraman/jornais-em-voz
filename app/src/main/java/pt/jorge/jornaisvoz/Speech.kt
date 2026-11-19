package pt.jorge.jornaisvoz

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Converte texto em ficheiros WAV com a voz portuguesa do telemóvel. */
class TtsSynth(ctx: Context) : TextToSpeech.OnInitListener {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts = TextToSpeech(ctx.applicationContext, this)

    var voiceInfo: String = ""
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.complete(false); return
        }
        val pt = Locale.forLanguageTag("pt-PT")
        tts.setLanguage(pt)
        try {
            val voices = tts.voices?.filter {
                it.locale.language == "pt" && it.locale.country == "PT"
            } ?: emptyList()
            val best = voices.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
                ?: voices.maxByOrNull { it.quality }
            if (best != null) tts.voice = best
            voiceInfo = tts.voice?.let { "${it.name} (${it.locale})" } ?: "sem voz pt-PT"
        } catch (e: Exception) {
            voiceInfo = "voz por omissão"
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(true) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
        })
        ready.complete(true)
    }

    suspend fun synth(text: String, file: File, rate: Float): Boolean {
        if (!ready.await()) return false
        file.parentFile?.mkdirs()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        tts.setSpeechRate(rate)
        val r = tts.synthesizeToFile(text, Bundle(), file, id)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id); return false
        }
        val ok = withTimeoutOrNull(180_000) { done.await() } ?: false
        return ok && file.exists() && file.length() > 44
    }

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
    }
}

object SpeechText {
    fun headline(paper: Paper, i: Int, readLead: Boolean): String {
        val a = paper.articles[i]
        val sb = StringBuilder()
        if (i == 0) sb.append("${paper.name}. ${paper.articles.size} notícias. ")
        if (paper.id == Repo.SHARED_ID && a.section.isNotBlank()) sb.append(a.section).append(". ")
        sb.append(a.title.trim().trimEnd('.')).append(".")
        if (readLead && a.lead.isNotBlank() && a.lead.length <= 350) sb.append(" ").append(a.lead.trim())
        return sb.toString()
    }

    fun article(a: Article): String {
        val sb = StringBuilder()
        sb.append(a.title.trim().trimEnd('.')).append(". ")
        if (a.lead.isNotBlank()) sb.append(a.lead.trim()).append(" ")
        sb.append(a.text.trim())
        return sb.toString()
    }

    /** Divide em blocos: o primeiro curto (arranque rápido), os seguintes maiores. */
    fun chunks(text: String, first: Int = 350, rest: Int = 1500, hardMax: Int = 3500): List<String> {
        val sentences = text.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?…»”\"])\\s+"))
            .flatMap { s -> if (s.length <= hardMax) listOf(s) else s.chunked(hardMax) }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            val limit = if (out.isEmpty()) first else rest
            if (cur.isNotEmpty() && cur.length + s.length + 1 > limit) {
                out.add(cur.toString()); cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }
}

object WavUtil {
    /** Acrescenta silêncio no fim de um WAV PCM e corrige o cabeçalho. */
    fun appendSilence(file: File, ms: Int) {
        if (ms <= 0 || !file.exists()) return
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            if (len < 44) return
            val header = ByteArray(minOf(len, 8192L).toInt())
            raf.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return
            var pos = 12
            var channels = 1
            var rate = 22050
            var bits = 16
            var dataOff = -1
            while (pos + 8 <= header.size) {
                val id = String(header, pos, 4, Charsets.US_ASCII)
                val size = le32(header, pos + 4)
                if (id == "fmt ") {
                    channels = le16(header, pos + 10)
                    rate = le32(header, pos + 12)
                    bits = le16(header, pos + 22)
                }
                if (id == "data") { dataOff = pos + 8; break }
                if (size < 0) break
                pos += 8 + size + (size and 1)
            }
            if (dataOff < 0) return
            val block = maxOf(1, channels * bits / 8)
            var add = rate.toLong() * block * ms / 1000
            add -= add % block
            val fill: Byte = if (bits == 8) 0x80.toByte() else 0
            val buf = ByteArray(16384) { fill }
            raf.seek(len)
            var left = add
            while (left > 0) {
                val n = minOf(left, buf.size.toLong()).toInt()
                raf.write(buf, 0, n); left -= n
            }
            val total = len + add
            raf.seek((dataOff - 4).toLong()); writeLe32(raf, total - dataOff)
            raf.seek(4); writeLe32(raf, total - 8)
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun writeLe32(raf: RandomAccessFile, v: Long) {
        val i = v.toInt()
        raf.write(byteArrayOf(i.toByte(), (i shr 8).toByte(), (i shr 16).toByte(), (i shr 24).toByte()))
    }
}
