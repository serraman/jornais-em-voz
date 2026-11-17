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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class ProcessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    @Volatile private var lastStartId = 0
    private lateinit var tts: TtsSynth

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensureChannels(this)
        tts = TtsSynth(this)
        scope.launch {
            for ((pid, audioOnly) in queue) {
                try {
                    if (audioOnly) regenerateAudio(pid) else process(pid)
                } catch (e: Exception) {
                    Repo.load(this@ProcessService, pid)?.let {
                        it.status = Paper.STATUS_ERROR
                        it.message = e.message ?: e.toString()
                        Repo.save(this@ProcessService, it)
                    }
                }
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
            queue.trySend(pid to intent!!.getBooleanExtra(EXTRA_AUDIO_ONLY, false))
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

    private suspend fun process(pid: String) {
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
        p.status = Paper.STATUS_PROCESSING
        p.articles.clear()
        p.progress = 0
        update(p, "A abrir o PDF…")

        val src = Repo.sourcePdf(ctx, pid)
        val client = GeminiClient(prefs.apiKey, prefs.model)
        val merged = mutableListOf<Article>()
        var pendingContinuation: Article? = null
        var failedPages = 0
        var lastRequest = 0L

        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            val n = renderer.pageCount
            p.total = n
            for (i in 0 until n) {
                p.progress = i
                update(p, "A ler página ${i + 1} de $n")
                val jpeg = renderPage(renderer, i)
                // Ritmo: no máximo um pedido a cada 5 segundos (limites do escalão gratuito).
                val wait = 5000 - (System.currentTimeMillis() - lastRequest)
                if (wait > 0) delay(wait)
                lastRequest = System.currentTimeMillis()
                val res = try {
                    client.analyzePage(jpeg, i + 1, n)
                } catch (e: FatalError) {
                    throw e
                } catch (e: Exception) {
                    failedPages++
                    null
                } ?: continue

                if (p.name.isBlank() && res.paperName.isNotBlank()) p.name = res.paperName
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
                p.articles = merged.toMutableList()
                Repo.save(ctx, p)
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        if (merged.isEmpty()) {
            p.status = Paper.STATUS_ERROR
            p.message = "Não foi possível encontrar notícias neste PDF."
            Repo.save(ctx, p)
            Notif.done(ctx, p)
            return
        }
        p.articles = merged
        p.progress = p.total
        buildHeadlines(p, prefs)
        p.status = Paper.STATUS_READY
        p.message = "${merged.size} notícias" + if (failedPages > 0) " ($failedPages páginas não lidas)" else ""
        Repo.save(ctx, p)
        Notif.done(ctx, p)
    }

    private suspend fun regenerateAudio(pid: String) {
        val p = Repo.load(this, pid) ?: return
        if (p.articles.isEmpty()) return
        val prev = p.status
        p.status = Paper.STATUS_PROCESSING
        buildHeadlines(p, Prefs(this))
        p.status = if (prev == Paper.STATUS_ERROR) Paper.STATUS_ERROR else Paper.STATUS_READY
        p.message = "${p.articles.size} notícias"
        Repo.save(this, p)
    }

    private suspend fun buildHeadlines(p: Paper, prefs: Prefs) {
        val t = synth()
        for (i in p.articles.indices) {
            update(p, "A preparar áudio ${i + 1} de ${p.articles.size}")
            val f = Repo.headlineFile(this, p.id, i)
            f.delete()
            if (t.synth(SpeechText.headline(p, i, prefs.readLead), f, prefs.speechRate)) {
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
        const val EXTRA_PID = "pid"
        const val EXTRA_AUDIO_ONLY = "audioOnly"

        fun start(ctx: Context, pid: String, audioOnly: Boolean = false) {
            val i = Intent(ctx, ProcessService::class.java)
                .putExtra(EXTRA_PID, pid)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            ContextCompat.startForegroundService(ctx, i)
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

    fun show(ctx: Context, id: Int, n: Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
