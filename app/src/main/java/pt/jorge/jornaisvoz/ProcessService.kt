package pt.jorge.jornaisvoz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class ProcessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Task(val pid: String, val mode: Int, val url: String?)
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    @Volatile private var lastStartId = 0
    private lateinit var tts: TtsSynth

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensureChannels(this)
        tts = TtsSynth(this)
        scope.launch {
            for ((pid, mode, url) in queue) {
                active.add(pid)
                try {
                    when (mode) {
                        MODE_AUDIO -> regenerateAudio(pid)
                        MODE_LINK -> processLink(url ?: "")
                        else -> process(pid, resume = mode == MODE_RESUME)
                    }
                } catch (e: Exception) {
                    if (mode == MODE_LINK) {
                        linkFailed(e.message ?: e.toString())
                    } else Repo.load(this@ProcessService, pid)?.let {
                        it.status = Paper.STATUS_ERROR
                        it.message = e.message ?: e.toString()
                        Repo.save(this@ProcessService, it)
                    }
                }
                active.remove(pid)
                stopRequests.remove(pid)
                if (pendingCount.decrementAndGet() <= 0) {
                    ServiceCompat.stopForeground(this@ProcessService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notif.PROGRESS_ID, Notif.progress(this, "A preparar jornal…", 0, 0), type)
        val pid = intent?.getStringExtra(EXTRA_PID)
        if (pid != null) {
            pendingCount.incrementAndGet()
            queue.trySend(Task(pid, intent!!.getIntExtra(EXTRA_MODE, MODE_FULL), intent.getStringExtra(EXTRA_URL)))
        } else if (pendingCount.get() <= 0) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        tts.shutdown()
        super.onDestroy()
    }

    private fun synth(): TtsSynth = tts

    private fun update(p: Paper, msg: String) {
        p.message = msg
        Repo.save(this, p)
        Notif.show(this, Notif.PROGRESS_ID, Notif.progress(this, "${p.name}: $msg", p.progress, p.total))
    }

    private suspend fun process(pid: String, resume: Boolean) {
        val ctx = this
        val p = Repo.load(ctx, pid) ?: return
        val prefs = Prefs(ctx)
        Repo.cleanup(ctx, prefs.keepDays)
        if (prefs.apiKey.isBlank()) {
            p.status = Paper.STATUS_ERROR
            p.message = "Falta a chave da API Gemini. Abra a app > Definições."
            Repo.save(ctx, p)
            Notif.done(ctx, p)
            return
        }
        p.name = Repo.nameFromFile(p.name + ".pdf")
        p.status = Paper.STATUS_PROCESSING
        p.progress = 0
        if (!resume) File(Repo.dir(ctx, pid), "pages").deleteRecursively()
        update(p, "A abrir o PDF…")

        val src = Repo.sourcePdf(ctx, pid)
        val client = GeminiClient(prefs.apiKey, prefs.model)
        client.onStatus = { msg -> update(p, msg) }
        client.pickFallback()

        // 1) Prepara as imagens das páginas que faltam ler.
        val pagesDir = File(cacheDir, "pages_$pid").apply { deleteRecursively(); mkdirs() }
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val n = renderer.pageCount
        p.total = n
        val todo = (0 until n).filter { !Repo.pageResultFile(ctx, pid, it).exists() }
        try {
            for ((k, i) in todo.withIndex()) {
                if (pid in stopRequests) break
                update(p, "A preparar página ${k + 1} de ${todo.size}")
                File(pagesDir, "$i.jpg").writeBytes(renderPage(renderer, i))
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        // 2) Envia ao Gemini 3 páginas de cada vez, pela ordem das páginas.
        val already = n - todo.size
        val done = AtomicInteger(already)
        val failed = AtomicInteger(0)
        var fatal: FatalError? = null
        val sem = Semaphore(PARALLEL)
        val pace = Mutex()
        var lastRequest = 0L
        p.progress = already
        update(p, "A ler páginas com o Gemini ($already de $n)")
        val workers = SupervisorJob()
        val wscope = CoroutineScope(Dispatchers.IO + workers)
        for (i in todo) {
            wscope.launch {
                sem.withPermit {
                    if (pid in stopRequests) return@withPermit
                    val img = File(pagesDir, "$i.jpg")
                    if (!img.exists()) return@withPermit
                    pace.withLock {
                        val wait = 2500 - (System.currentTimeMillis() - lastRequest)
                        if (wait > 0) delay(wait)
                        lastRequest = System.currentTimeMillis()
                    }
                    try {
                        val res = runInterruptible { client.analyzePage(img.readBytes(), i + 1, n) }
                        Repo.pageResultFile(ctx, pid, i).writeText(res.raw)
                    } catch (e: FatalError) {
                        fatal = e
                        workers.cancel()
                        return@withPermit
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                    }
                    val d = done.incrementAndGet()
                    p.progress = d
                    update(p, "A ler páginas com o Gemini ($d de $n)")
                }
            }
        }
        // Espera que acabem, ou que o utilizador carregue em "Parar e ouvir".
        while (workers.children.any { it.isActive }) {
            if (pid in stopRequests) {
                workers.cancel()
                break
            }
            delay(500)
        }
        pagesDir.deleteRecursively()
        val stopped = pid in stopRequests
        fatal?.let { if (!stopped) throw it }

        // 3) Junta as notícias das páginas lidas, pela ordem das páginas.
        val merged = mutableListOf<Article>()
        var pendingContinuation: Article? = null
        var pagesRead = 0
        for (i in 0 until n) {
            val f = Repo.pageResultFile(ctx, pid, i)
            val res = if (f.exists()) runCatching { GeminiClient.parseResult(f.readText()) }.getOrNull() else null
            if (res == null) {
                pendingContinuation = null
                continue
            }
            pagesRead++
            for (a in res.articles) {
                if (a.type == "chamada") continue
                if (a.continuesPrevious && merged.isNotEmpty() && (a.title.isBlank() || pendingContinuation != null)) {
                    val target = pendingContinuation ?: merged.last()
                    target.text = target.text.trimEnd() + "\n" + a.text
                    if (!a.continuesNext) pendingContinuation = null
                    continue
                }
                if (a.text.length < 80) continue
                val title = a.title.ifBlank { a.text.split(' ').take(10).joinToString(" ") + "…" }
                val art = Article(i + 1, a.section, title, a.lead, a.text)
                merged.add(art)
                pendingContinuation = if (a.continuesNext) art else null
            }
        }
        p.pagesRead = pagesRead

        if (merged.isEmpty()) {
            p.status = Paper.STATUS_ERROR
            p.message = if (stopped) "Parado antes de ler alguma notícia. Carregue em Continuar."
            else "Não foi possível encontrar notícias neste PDF."
            Repo.save(ctx, p)
            if (!stopped) Notif.done(ctx, p)
            return
        }
        p.articles = merged
        p.progress = p.total
        buildHeadlines(p, prefs)
        p.status = Paper.STATUS_READY
        p.message = if (pagesRead < n)
            "${merged.size} notícias ($pagesRead de $n páginas lidas). Pode carregar em Continuar."
        else "${merged.size} notícias"
        Repo.save(ctx, p)
        if (pagesRead == n) src.delete() // poupa espaço: o PDF já não é preciso
        Notif.done(ctx, p)
    }

    private suspend fun processLink(url: String) {
        val ctx = this
        val prefs = Prefs(ctx)
        val p = Repo.shared(ctx)
        if (prefs.apiKey.isBlank()) {
            linkFailed("Falta a chave da API Gemini. Abra a app > Definições.")
            return
        }
        update(p, "A abrir a notícia…")
        val page = PageFetcher.fetch(ctx, url) { msg -> update(p, msg) }
        if (page.text.length < 300) {
            linkFailed("A página não tinha texto suficiente.")
            return
        }
        update(p, "A extrair a notícia com o Gemini…")
        val client = GeminiClient(prefs.apiKey, prefs.model)
        client.onStatus = { msg -> update(p, msg) }
        client.pickFallback()
        val wa = client.extractArticle(page.text, page.url)
        if (wa.paid) {
            val what = listOf(wa.site, wa.title).filter { it.isNotBlank() }.joinToString(": ")
            linkFailed(what.ifBlank { "conteúdo para assinantes" }, title = "Notícia paga", prefix = "Notícia paga, não foi guardada")
            return
        }
        if (wa.text.length < 150) {
            linkFailed("Não encontrei o texto da notícia nesta página.")
            return
        }
        val fresh = Repo.shared(ctx)
        val title = wa.title.ifBlank { wa.text.split(' ').take(10).joinToString(" ") + "…" }
        fresh.articles.removeAll { it.title == title }
        fresh.articles.add(0, Article(0, wa.site, title, wa.lead, wa.text))
        while (fresh.articles.size > MAX_SHARED) fresh.articles.removeAt(fresh.articles.size - 1)
        fresh.status = Paper.STATUS_PROCESSING
        buildHeadlines(fresh, prefs)
        fresh.status = Paper.STATUS_READY
        fresh.message = "${fresh.articles.size} notícias · última: $title"
        Repo.save(ctx, fresh)
        Notif.show(ctx, title.hashCode(), Notif.simple(ctx, "Notícia pronta a ouvir", title))
    }

    private fun linkFailed(
        msg: String,
        title: String = "Não foi possível ler a notícia",
        prefix: String = "Última notícia não lida",
    ) {
        val p = Repo.shared(this)
        p.status = if (p.articles.isEmpty()) Paper.STATUS_ERROR else Paper.STATUS_READY
        p.message = "$prefix: $msg"
        Repo.save(this, p)
        Notif.show(this, 4242, Notif.simple(this, title, msg))
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, "$title: $msg", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun regenerateAudio(pid: String) {
        val p = Repo.load(this, pid) ?: return
        if (p.articles.isEmpty()) return
        val prev = p.status
        p.status = Paper.STATUS_PROCESSING
        buildHeadlines(p, Prefs(this))
        p.status = if (prev == Paper.STATUS_ERROR) Paper.STATUS_ERROR else Paper.STATUS_READY
        val inc = Prefs(this).includedIndices(p).size
        p.message = (if (inc < p.articles.size) "$inc de ${p.articles.size} notícias (secções escolhidas)" else "${p.articles.size} notícias") +
            if (p.total > 0 && p.pagesRead in 1 until p.total) " (${p.pagesRead} de ${p.total} páginas lidas)" else ""
        Repo.save(this, p)
        if (prev == Paper.STATUS_PROCESSING) Notif.done(this, p) // jornal recebido do PC
    }

    private suspend fun buildHeadlines(p: Paper, prefs: Prefs) {
        val t = synth()
        val included = prefs.includedIndices(p)
        val first = included.firstOrNull()
        p.articles.indices.forEach { Repo.headlineFile(this, p.id, it).delete() }
        for ((k, i) in included.withIndex()) {
            update(p, "A preparar áudio ${k + 1} de ${included.size}")
            val f = Repo.headlineFile(this, p.id, i)
            val text = SpeechText.headline(p, i, prefs.readLead, intro = i == first, count = included.size)
            if (t.synth(text, f, prefs.speechRate)) {
                WavUtil.appendSilence(f, prefs.pauseSeconds * 1000)
            }
        }
    }

    private fun renderPage(r: PdfRenderer, i: Int): ByteArray {
        val page = r.openPage(i)
        try {
            val w = 1800
            val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(100, 4000)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            return out.toByteArray()
        } finally {
            page.close()
        }
    }

    companion object {
        const val PARALLEL = 3
        /** Jornais a ser processados neste momento. */
        val active: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val EXTRA_PID = "pid"
        const val EXTRA_MODE = "mode"
        const val MODE_FULL = 0
        const val MODE_RESUME = 1
        const val MODE_AUDIO = 2
        const val MODE_LINK = 3
        const val EXTRA_URL = "url"
        const val MAX_SHARED = 30

        /** Pedidos de "Parar e ouvir". */
        val stopRequests: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun start(ctx: Context, pid: String, mode: Int = MODE_FULL) {
            val i = Intent(ctx, ProcessService::class.java)
                .putExtra(EXTRA_PID, pid)
                .putExtra(EXTRA_MODE, mode)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun startLink(ctx: Context, url: String) {
            val i = Intent(ctx, ProcessService::class.java)
                .putExtra(EXTRA_PID, Repo.SHARED_ID)
                .putExtra(EXTRA_MODE, MODE_LINK)
                .putExtra(EXTRA_URL, url)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun requestStop(pid: String) {
            stopRequests.add(pid)
        }
    }
}

object Notif {
    const val PROGRESS_ID = 1001
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Processamento", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Jornais prontos", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun progress(ctx: Context, text: String, cur: Int, max: Int): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Jornais em Voz")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, cur, max == 0)
            .setContentIntent(openApp(ctx))
            .build()

    fun done(ctx: Context, p: Paper) {
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (p.isReady) "${p.name} pronto a ouvir" else "${p.name}: erro")
            .setContentText(p.message)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        show(ctx, p.id.hashCode(), n)
    }

    fun simple(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun show(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
