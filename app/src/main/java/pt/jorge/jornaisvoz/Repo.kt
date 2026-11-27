package pt.jorge.jornaisvoz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Article(
    val page: Int,
    val section: String,
    val title: String,
    val lead: String,
    var text: String,
)

class Paper(
    val id: String,
    var name: String,
    val created: Long,
) {
    var status: String = STATUS_NEW
    var message: String = ""
    var progress: Int = 0
    var total: Int = 0
    var pagesRead: Int = 0
    var articles: MutableList<Article> = mutableListOf()

    val isReady get() = status == STATUS_READY

    val dateLabel: String
        get() = SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("pt-PT")).format(Date(created))

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("created", created)
        put("status", status); put("message", message)
        put("progress", progress); put("total", total); put("pagesRead", pagesRead)
        put("articles", JSONArray().apply {
            articles.forEach { a ->
                put(JSONObject().apply {
                    put("page", a.page); put("section", a.section); put("title", a.title)
                    put("lead", a.lead); put("text", a.text)
                })
            }
        })
    }

    companion object {
        const val STATUS_NEW = "new"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_READY = "ready"
        const val STATUS_ERROR = "error"

        fun fromJson(o: JSONObject): Paper {
            val p = Paper(o.getString("id"), o.optString("name"), o.optLong("created"))
            p.status = o.optString("status", STATUS_NEW)
            p.message = o.optString("message")
            p.progress = o.optInt("progress")
            p.total = o.optInt("total")
            p.pagesRead = o.optInt("pagesRead")
            val arr = o.optJSONArray("articles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                p.articles.add(
                    Article(
                        a.optInt("page"), a.optString("section"), a.optString("title"),
                        a.optString("lead"), a.optString("text")
                    )
                )
            }
            return p
        }
    }
}

object Repo {
    private val lock = Any()
    const val SHARED_ID = "partilhadas"

    /** Coleção das notícias partilhadas a partir de links (Google Notícias, sites). */
    fun shared(ctx: Context): Paper = load(ctx, SHARED_ID) ?: Paper(SHARED_ID, "Notícias partilhadas", System.currentTimeMillis()).also {
        it.status = Paper.STATUS_READY
        save(ctx, it)
    }

    fun root(ctx: Context) = File(ctx.filesDir, "papers").apply { mkdirs() }
    fun dir(ctx: Context, pid: String) = File(root(ctx), pid)
    fun sourcePdf(ctx: Context, pid: String) = File(dir(ctx, pid), "source.pdf")
    fun headlineFile(ctx: Context, pid: String, i: Int) = File(dir(ctx, pid), "h_$i.wav")
    fun pageResultFile(ctx: Context, pid: String, i: Int) = File(File(dir(ctx, pid), "pages").apply { mkdirs() }, "$i.json")

    fun create(ctx: Context, displayName: String): Paper {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val pid = "$stamp-${(1000..9999).random()}"
        val p = Paper(pid, nameFromFile(displayName), System.currentTimeMillis())
        dir(ctx, pid).mkdirs()
        save(ctx, p)
        return p
    }

    /** Importa um ficheiro ".jornal.json" criado pelo programa do PC. */
    fun importPackage(ctx: Context, json: String): Paper {
        val o = JSONObject(json.trimStart('\uFEFF'))
        require(o.optString("formato") == "jornais-em-voz") { "Ficheiro não reconhecido" }
        val p = create(ctx, "Jornal.pdf")
        p.name = o.optString("nome").ifBlank { "Jornal" }
        val arr = o.optJSONArray("artigos") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            p.articles.add(
                Article(
                    a.optInt("page"), a.optString("section"), a.optString("title"),
                    a.optString("lead"), a.optString("text")
                )
            )
        }
        require(p.articles.isNotEmpty()) { "O ficheiro não tem notícias" }
        p.total = o.optInt("total_paginas")
        p.pagesRead = o.optInt("paginas_lidas")
        p.status = Paper.STATUS_PROCESSING
        p.message = "Recebido do PC. A preparar o áudio…"
        save(ctx, p)
        return p
    }

    fun save(ctx: Context, p: Paper) = synchronized(lock) {
        val d = dir(ctx, p.id).apply { mkdirs() }
        val tmp = File(d, "paper.json.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(File(d, "paper.json"))
    }

    fun load(ctx: Context, pid: String): Paper? = synchronized(lock) {
        val f = File(dir(ctx, pid), "paper.json")
        if (!f.exists()) return null
        try {
            Paper.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<Paper> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(ctx, it.name) }
            .sortedWith(compareByDescending<Paper> { it.id == SHARED_ID }.thenByDescending { it.created })

    fun delete(ctx: Context, pid: String) = synchronized(lock) {
        dir(ctx, pid).deleteRecursively()
    }

    fun cleanup(ctx: Context, keepDays: Int) {
        val limit = System.currentTimeMillis() - keepDays * 24L * 3600 * 1000
        list(ctx).filter { it.id != SHARED_ID && it.created < limit && it.status != Paper.STATUS_PROCESSING }
            .forEach { delete(ctx, it.id) }
    }

    /** "_20261117-PT__Público_LX_261117_101020.pdf" -> "Público" */
    fun nameFromFile(name: String): String {
        var base = name.substringBeforeLast('.')
        base = base.replace(Regex("\\([^)]*\\)"), " ")             // "(20261117-PT)"
            .replace(Regex("^[_\\s-]*\\d{8}-[A-Za-z]{2}_*"), " ")    // "_20261117-PT__"
        val tokens = base.split(Regex("[_\\s]+")).filter { it.isNotBlank() }
        val words = mutableListOf<String>()
        for (t in tokens) {
            val isCode = t.any { it.isDigit() } || (t.length <= 3 && t == t.uppercase() && t.any { it.isLetter() })
            if (isCode) { if (words.isNotEmpty()) break else continue }
            words.add(t)
        }
        return words.joinToString(" ").replace('-', ' ').trim().ifBlank { "Jornal" }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply()

    var pauseSeconds: Int
        get() = sp.getInt("pause", 3)
        set(v) = sp.edit().putInt("pause", v.coerceIn(1, 10)).apply()

    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v.coerceIn(0.5f, 2.0f)).apply()

    var readLead: Boolean
        get() = sp.getBoolean("readLead", true)
        set(v) = sp.edit().putBoolean("readLead", v).apply()

    var keepDays: Int
        get() = sp.getInt("keepDays", 3)
        set(v) = sp.edit().putInt("keepDays", v.coerceIn(1, 30)).apply()

    /** Motor de voz (pacote) escolhido; vazio = motor por omissão do telemóvel. */
    var ttsEngine: String
        get() = sp.getString("ttsEngine", "") ?: ""
        set(v) = sp.edit().putString("ttsEngine", v).apply()

    /** Nome da voz escolhida; vazio = escolha automática. */
    var voiceName: String
        get() = sp.getString("voiceName", "") ?: ""
        set(v) = sp.edit().putString("voiceName", v).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
