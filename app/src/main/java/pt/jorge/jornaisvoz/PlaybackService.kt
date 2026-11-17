package pt.jorge.jornaisvoz

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Leitor de áudio (telemóvel e Android Auto).
 *
 * A lista de reprodução tem um item por título ("h:jornal:i"), cada um com uma pausa no fim.
 * Botão "seguinte" (volante, ecrã ou "Ok Google, seguinte") durante um título => lê a notícia
 * completa, inserindo os blocos "a:jornal:i:k" logo a seguir ao título.
 * Botão "seguinte" durante a notícia => salta para o título seguinte.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var exo: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()
    private lateinit var tts: TtsSynth
    private val prefetched = HashMap<String, Deferred<File?>>()
    private var articleJob: Job? = null

    private val artDir by lazy { File(cacheDir, "art").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        artDir.listFiles()?.forEach { it.delete() }
        tts = TtsSynth(this)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { onTransition(it) }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Ficheiro em falta ou estragado: passa ao seguinte.
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                }
            }
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, NewsPlayer(exo), LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        tts.shutdown()
        super.onDestroy()
    }

    // ---------- Identificadores ----------

    private data class Id(val type: Char, val pid: String, val i: Int, val k: Int)

    private fun parseId(s: String): Id? {
        val parts = s.split(':')
        return when {
            parts.size == 2 && parts[0] == "p" -> Id('p', parts[1], 0, 0)
            parts.size == 3 && parts[0] == "h" -> Id('h', parts[1], parts[2].toIntOrNull() ?: return null, 0)
            parts.size == 4 && parts[0] == "a" ->
                Id('a', parts[1], parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: 0)
            else -> null
        }
    }

    private fun indexOfId(id: String): Int {
        for (j in 0 until exo.mediaItemCount) if (exo.getMediaItemAt(j).mediaId == id) return j
        return -1
    }

    private fun articleRange(pid: String, i: Int): IntRange? {
        val prefix = "a:$pid:$i:"
        var first = -1
        var last = -1
        for (j in 0 until exo.mediaItemCount) {
            if (exo.getMediaItemAt(j).mediaId.startsWith(prefix)) {
                if (first < 0) first = j
                last = j
            }
        }
        return if (first < 0) null else first..last
    }

    // ---------- Itens ----------

    private fun rootItem() = MediaItem.Builder()
        .setMediaId(ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("Jornais")
                .setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()

    private fun paperItem(p: Paper) = MediaItem.Builder()
        .setMediaId("p:${p.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("${p.name} · ${p.dateLabel}")
                .setSubtitle("${p.articles.size} notícias")
                .setIsBrowsable(true).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()

    private fun headlineItem(p: Paper, i: Int): MediaItem {
        val a = p.articles[i]
        val sub = "${p.name} · ${i + 1}/${p.articles.size}" + if (a.section.isNotBlank()) " · ${a.section}" else ""
        return MediaItem.Builder()
            .setMediaId("h:${p.id}:$i")
            .setUri(Uri.fromFile(Repo.headlineFile(this, p.id, i)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(a.title)
                    .setArtist(sub)
                    .setSubtitle(sub)
                    .setAlbumTitle(p.name)
                    .setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_NEWS).build()
            ).build()
    }

    private fun articleItem(p: Paper, i: Int, k: Int, n: Int, f: File): MediaItem {
        val a = p.articles[i]
        return MediaItem.Builder()
            .setMediaId("a:${p.id}:$i:$k")
            .setUri(Uri.fromFile(f))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(a.title)
                    .setArtist("Notícia completa · parte ${k + 1} de $n")
                    .setAlbumTitle(p.name)
                    .setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_NEWS).build()
            ).build()
    }

    private fun headlines(p: Paper): List<MediaItem> =
        p.articles.indices
            .filter { Repo.headlineFile(this, p.id, it).exists() }
            .map { headlineItem(p, it) }

    // ---------- Lógica dos botões ----------

    private fun onNext() {
        val id = exo.currentMediaItem?.let { parseId(it.mediaId) }
        when (id?.type) {
            'h' -> readArticle(id.pid, id.i)
            'a' -> skipArticle(id.pid, id.i)
            else -> if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
        }
    }

    private fun onSkip() {
        val id = exo.currentMediaItem?.let { parseId(it.mediaId) } ?: return
        when (id.type) {
            'h' -> {
                val nextH = indexOfId("h:${id.pid}:${id.i + 1}")
                if (nextH >= 0) exo.seekToDefaultPosition(nextH)
            }
            'a' -> skipArticle(id.pid, id.i)
        }
    }

    private fun onPrevious() {
        val id = exo.currentMediaItem?.let { parseId(it.mediaId) }
        when (id?.type) {
            'h' -> {
                val prev = indexOfId("h:${id.pid}:${id.i - 1}")
                if (prev >= 0) exo.seekToDefaultPosition(prev) else exo.seekTo(0)
            }
            'a' -> {
                articleJob?.cancel()
                val h = indexOfId("h:${id.pid}:${id.i}")
                if (h >= 0) {
                    exo.seekToDefaultPosition(h)
                    articleRange(id.pid, id.i)?.let { exo.removeMediaItems(it.first, it.last + 1) }
                }
            }
            else -> exo.seekToPreviousMediaItem()
        }
    }

    private fun readArticle(pid: String, i: Int) {
        val h = indexOfId("h:$pid:$i")
        if (h < 0) return
        val existing = articleRange(pid, i)
        if (existing != null) {
            exo.seekToDefaultPosition(existing.first); exo.play(); return
        }
        val p = Repo.load(this, pid) ?: return
        val art = p.articles.getOrNull(i) ?: return
        val chunks = SpeechText.chunks(SpeechText.article(art))
        if (chunks.isEmpty()) return
        val rate = Prefs(this).speechRate
        val key = "$pid:$i"
        val pre = prefetched[key]
        if (pre == null || !pre.isCompleted) exo.pause() // espera pelo primeiro bloco
        articleJob?.cancel()
        articleJob = scope.launch {
            for ((k, chunk) in chunks.withIndex()) {
                val made: File? = if (k == 0) {
                    pre?.await() ?: synthTo(chunk, pid, i, 0, rate)
                } else {
                    synthTo(chunk, pid, i, k, rate)
                }
                val f = made ?: break
                val item = articleItem(p, i, k, chunks.size, f)
                if (k == 0) {
                    val hNow = indexOfId("h:$pid:$i")
                    if (hNow < 0) return@launch
                    exo.addMediaItem(hNow + 1, item)
                    exo.seekToDefaultPosition(hNow + 1)
                    exo.play()
                } else {
                    val range = articleRange(pid, i) ?: return@launch // o utilizador saltou
                    exo.addMediaItem(range.last + 1, item)
                }
            }
        }
    }

    private fun skipArticle(pid: String, i: Int) {
        articleJob?.cancel()
        val nextH = indexOfId("h:$pid:${i + 1}")
        if (nextH >= 0) exo.seekToDefaultPosition(nextH) else exo.pause()
        articleRange(pid, i)?.let { r ->
            if (nextH >= 0) exo.removeMediaItems(r.first, r.last + 1)
        }
    }

    private suspend fun synthTo(text: String, pid: String, i: Int, k: Int, rate: Float): File? {
        val f = File(artDir, "a_${pid}_${i}_$k.wav")
        if (f.exists() && f.length() > 44) return f
        return if (tts.synth(text, f, rate)) f else null
    }

    private fun onTransition(item: MediaItem) {
        val id = parseId(item.mediaId) ?: return
        if (id.type != 'h') return
        // Ao entrar num título, retira notícias completas já ouvidas de outros títulos.
        scope.launch {
            var j = exo.mediaItemCount - 1
            while (j >= 0) {
                val other = parseId(exo.getMediaItemAt(j).mediaId)
                if (other != null && other.type == 'a' && !(other.pid == id.pid && other.i == id.i) &&
                    j != exo.currentMediaItemIndex) {
                    exo.removeMediaItem(j)
                }
                j--
            }
        }
        // Prepara já o início da notícia, para arrancar sem espera se for pedida.
        val key = "${id.pid}:${id.i}"
        if (!prefetched.containsKey(key)) {
            val rate = Prefs(this).speechRate
            prefetched[key] = scope.async {
                val p = Repo.load(this@PlaybackService, id.pid) ?: return@async null
                val art = p.articles.getOrNull(id.i) ?: return@async null
                val first = SpeechText.chunks(SpeechText.article(art)).firstOrNull() ?: return@async null
                synthTo(first, id.pid, id.i, 0, rate)
            }
        }
    }

    private inner class NewsPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToNext() = onNext()
        override fun seekToNextMediaItem() = onNext()
        override fun seekToPrevious() = onPrevious()
        override fun seekToPreviousMediaItem() = onPrevious()

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            if (mediaItemCount == 0) return base
            return base.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            getAvailableCommands().contains(command)
    }

    // ---------- Sessão / biblioteca (Android Auto) ----------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val readCmd = SessionCommand(CMD_READ, Bundle.EMPTY)
        private val skipCmd = SessionCommand(CMD_SKIP, Bundle.EMPTY)

        @Suppress("DEPRECATION")
        private val buttons: ImmutableList<CommandButton> = ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Ler notícia")
                .setIconResId(R.drawable.ic_read)
                .setSessionCommand(readCmd)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Próximo título")
                .setIconResId(R.drawable.ic_skip)
                .setSessionCommand(skipCmd)
                .build()
        )

        @Suppress("DEPRECATION")
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(readCmd).add(skipCmd).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .setCustomLayout(buttons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_READ -> {
                    val id = exo.currentMediaItem?.let { parseId(it.mediaId) }
                    if (id?.type == 'h') readArticle(id.pid, id.i)
                }
                CMD_SKIP -> onSkip()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val ctx = this@PlaybackService
            val items: List<MediaItem> = if (parentId == ROOT) {
                Repo.list(ctx).filter { it.isReady }.map { paperItem(it) }
            } else {
                val id = parseId(parentId)
                if (id?.type == 'p') Repo.load(ctx, id.pid)?.let { headlines(it) } ?: emptyList()
                else emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val ctx = this@PlaybackService
            val id = parseId(mediaId)
            val p = id?.let { Repo.load(ctx, it.pid) }
            val item = when {
                mediaId == ROOT -> rootItem()
                id == null || p == null -> null
                id.type == 'p' -> paperItem(p)
                id.type == 'h' && id.i < p.articles.size -> headlineItem(p, id.i)
                else -> null
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val ctx = this@PlaybackService
            val req = mediaItems.getOrNull(if (startIndex in mediaItems.indices) startIndex else 0)
            val id = req?.let { parseId(it.mediaId) }
            val p = id?.let { Repo.load(ctx, it.pid) }
            if (id == null || p == null || !p.isReady) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                )
            }
            val list = headlines(p)
            val start = if (id.type == 'h') list.indexOfFirst { it.mediaId == "h:${p.id}:${id.i}" }.coerceAtLeast(0) else 0
            prefetched.clear()
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(list, start, 0))
        }
    }

    companion object {
        const val ROOT = "root"
        const val CMD_READ = "pt.jorge.jornaisvoz.READ"
        const val CMD_SKIP = "pt.jorge.jornaisvoz.SKIP"
    }
}
