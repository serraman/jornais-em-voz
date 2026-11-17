package pt.jorge.jornaisvoz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var nowPlaying: TextView
    private lateinit var playPause: Button
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.let { if (it.isDone && !it.isCancelled) runCatching { it.get() }.getOrNull() else null }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature = ""
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        root.addView(row(
            button("Definições") { startActivity(Intent(this, SettingsActivity::class.java)) },
            button("Ajuda") { showHelp() }
        ))
        root.addView(TextView(this).apply {
            text = "Para juntar um jornal: no WhatsApp, abra o PDF (ou mantenha o dedo em cima dele) > Partilhar > Jornais em Voz."
            setPadding(0, dp(8), 0, dp(8))
        })

        nowPlaying = TextView(this).apply {
            text = "Nada a tocar"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(nowPlaying)
        playPause = button("▶") {
            controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
        }
        root.addView(row(
            button("⏮") { controller?.seekToPrevious() },
            playPause,
            button("Ler notícia") {
                controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_READ, Bundle.EMPTY), Bundle.EMPTY)
            },
            button("Próximo") {
                controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_SKIP, Bundle.EMPTY), Bundle.EMPTY)
            }
        ))

        root.addView(TextView(this).apply {
            text = "Jornais"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Prefs(this).apiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Bem-vindo")
                .setMessage("Antes de começar, cole a chave da API Gemini nas Definições.")
                .setPositiveButton("Abrir Definições") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Mais tarde", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        lastSignature = ""
        handler.post(refresher)
    }

    override fun onStop() {
        handler.removeCallbacks(refresher)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun refresh() {
        val c = controller
        nowPlaying.text = c?.mediaMetadata?.title?.let { "A tocar: $it" } ?: "Nada a tocar"
        playPause.text = if (c?.isPlaying == true) "⏸" else "▶"

        val papers = Repo.list(this)
        val sig = papers.joinToString("|") { "${it.id}${it.status}${it.message}${it.progress}${it.id in ProcessService.active}${it.id in ProcessService.stopRequests}" }
        if (sig == lastSignature) return
        lastSignature = sig
        list.removeAllViews()
        if (papers.isEmpty()) {
            list.addView(TextView(this).apply { text = "Ainda não há jornais. Partilhe um PDF a partir do WhatsApp." })
        }
        for (p in papers) list.addView(paperCard(p))
    }

    private fun paperCard(p: Paper): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFEFF3F8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        card.addView(TextView(this).apply {
            text = "${p.name} · ${p.dateLabel}"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        val busy = p.id in ProcessService.active
        val status = when {
            p.status == Paper.STATUS_READY -> p.message.ifBlank { "${p.articles.size} notícias" }
            p.status == Paper.STATUS_ERROR -> "Erro: ${p.message}"
            !busy && p.status == Paper.STATUS_PROCESSING -> "Interrompido. Carregue em Refazer."
            else -> p.message.ifBlank { "A preparar…" }
        }
        card.addView(TextView(this).apply { text = status })

        val buttons = mutableListOf<View>()
        val stopping = p.id in ProcessService.stopRequests
        if (busy && p.status == Paper.STATUS_PROCESSING && !stopping) {
            buttons += button("Parar e ouvir o que já leu") {
                ProcessService.requestStop(p.id); lastSignature = ""; refresh()
            }
        }
        if (busy && stopping) {
            card.addView(TextView(this).apply { text = "A parar e a preparar o áudio das páginas já lidas…" })
        }
        if (p.isReady) buttons += button("Ouvir") { play(p) }
        if (p.isReady) buttons += button("Títulos") { showTitles(p) }
        if (!busy && Repo.sourcePdf(this, p.id).exists() && p.total > 0 && p.pagesRead < p.total) {
            buttons += button("Continuar") { ProcessService.start(this, p.id, ProcessService.MODE_RESUME) }
        }
        if (!busy && Repo.sourcePdf(this, p.id).exists()) {
            buttons += button("Refazer") { confirm("Voltar a ler o PDF com o Gemini?") { ProcessService.start(this, p.id) } }
        }
        if (!busy) {
            buttons += button("Apagar") { confirm("Apagar ${p.name}?") { Repo.delete(this, p.id); lastSignature = ""; refresh() } }
        }
        if (buttons.isNotEmpty()) card.addView(row(*buttons.toTypedArray()))
        return card
    }

    private fun play(p: Paper) {
        val c = controller ?: return
        c.setMediaItem(MediaItem.Builder().setMediaId("p:${p.id}").build())
        c.prepare()
        c.play()
    }

    private fun showTitles(p: Paper) {
        val items = p.articles.mapIndexed { i, a -> "${i + 1}. ${a.title}  (pág. ${a.page})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(items) { _, which ->
                val a = p.articles[which]
                AlertDialog.Builder(this)
                    .setTitle(a.title)
                    .setMessage((if (a.lead.isNotBlank()) a.lead + "\n\n" else "") + a.text)
                    .setPositiveButton("Fechar", null)
                    .show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Como usar")
            .setMessage(
                "1. No WhatsApp, partilhe o PDF do jornal com a app \"Jornais em Voz\". " +
                    "A app lê as páginas com o Gemini (alguns minutos) e avisa quando estiver pronto.\n\n" +
                    "2. No carro, abra Jornais em Voz no Android Auto e escolha o jornal.\n\n" +
                    "3. Ouve os títulos, com uma pausa entre eles. Durante o título ou a pausa:\n" +
                    "   • botão SEGUINTE do volante, \"Ok Google, seguinte\" ou botão \"Ler notícia\" = lê a notícia completa;\n" +
                    "   • durante a notícia, SEGUINTE = salta para o título seguinte;\n" +
                    "   • ANTERIOR = volta ao título anterior.\n\n" +
                    "Se a app não aparecer no Android Auto: Definições do Android Auto > toque 10 vezes em \"Versão\" " +
                    "> menu ⋮ > Definições de programador > ative \"Fontes desconhecidas\"."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("Sim") { _, _ -> action() }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }
}
