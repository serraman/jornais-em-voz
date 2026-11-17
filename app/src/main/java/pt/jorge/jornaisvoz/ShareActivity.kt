package pt.jorge.jornaisvoz

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Recebe PDFs partilhados (ex.: do WhatsApp) e põe-nos a processar. */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nenhum PDF recebido", Toast.LENGTH_LONG).show()
            finish(); return
        }
        Toast.makeText(this, "A receber ${uris.size} jornal(is)…", Toast.LENGTH_SHORT).show()
        Thread {
            val ids = mutableListOf<String>()
            var failed = 0
            for (u in uris) {
                try {
                    val name = displayName(u) ?: "Jornal.pdf"
                    val p = Repo.create(this, name)
                    contentResolver.openInputStream(u)!!.use { input ->
                        Repo.sourcePdf(this, p.id).outputStream().use { input.copyTo(it) }
                    }
                    p.message = "Na fila"
                    Repo.save(this, p)
                    ids.add(p.id)
                } catch (e: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                ids.forEach { ProcessService.start(this, it) }
                val msg = if (failed == 0) "A preparar ${ids.size} jornal(is). Será avisado quando estiver pronto."
                else "Não foi possível abrir $failed ficheiro(s)."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun displayName(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: u.lastPathSegment
}
