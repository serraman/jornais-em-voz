package pt.jorge.jornaisvoz

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var pause: EditText
    private lateinit var rate: EditText
    private lateinit var keep: EditText
    private lateinit var lead: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(2))
        }
        fun hint(t: String) = TextView(this).apply { text = t; textSize = 13f }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { f() }
        }

        root.addView(label("Chave da API Gemini"))
        key = EditText(this).apply {
            setText(prefs.apiKey)
            hint = "AIza…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        root.addView(key)
        root.addView(btn("Obter chave grátis (Google AI Studio)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })

        root.addView(label("Modelo Gemini"))
        model = EditText(this).apply { setText(prefs.model) }
        root.addView(model)
        root.addView(hint("Por omissão: ${Prefs.DEFAULT_MODEL}. Use \"Testar\" para ver os modelos disponíveis com a sua chave."))
        root.addView(btn("Testar chave e escolher modelo") { testKey() })

        root.addView(label("Pausa entre títulos (segundos)"))
        pause = EditText(this).apply {
            setText(prefs.pauseSeconds.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(pause)

        root.addView(label("Velocidade da voz (1.0 = normal)"))
        rate = EditText(this).apply {
            setText(prefs.speechRate.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(rate)

        lead = CheckBox(this).apply {
            text = "Ler também o subtítulo (entrada) depois do título"
            isChecked = prefs.readLead
        }
        root.addView(lead)

        root.addView(label("Guardar jornais durante (dias)"))
        keep = EditText(this).apply {
            setText(prefs.keepDays.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(keep)

        root.addView(btn("Guardar") { save() })
        root.addView(btn("Ver/instalar voz portuguesa (pt-PT)") {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: Exception) {
                Toast.makeText(this, "Abra Definições > Sistema > Idiomas > Saída de texto para voz", Toast.LENGTH_LONG).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        val prefs = Prefs(this)
        val audioChanged = pause.text.toString().toIntOrNull() != prefs.pauseSeconds ||
            rate.text.toString().replace(',', '.').toFloatOrNull() != prefs.speechRate ||
            lead.isChecked != prefs.readLead
        prefs.apiKey = key.text.toString()
        prefs.model = model.text.toString()
        pause.text.toString().toIntOrNull()?.let { prefs.pauseSeconds = it }
        rate.text.toString().replace(',', '.').toFloatOrNull()?.let { prefs.speechRate = it }
        prefs.readLead = lead.isChecked
        keep.text.toString().toIntOrNull()?.let { prefs.keepDays = it }
        if (audioChanged) {
            Repo.list(this).filter { it.isReady }.forEach { ProcessService.start(this, it.id, ProcessService.MODE_AUDIO) }
        }
        Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testKey() {
        val k = key.text.toString().trim()
        if (k.isBlank()) {
            Toast.makeText(this, "Cole primeiro a chave", Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(this, "A testar…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { GeminiClient(k, model.text.toString()).listModels() }
            runOnUiThread {
                result.onSuccess { models ->
                    val flash = models.filter { it.contains("flash") && !it.contains("image") && !it.contains("tts") && !it.contains("live") }
                        .ifEmpty { models }
                    AlertDialog.Builder(this)
                        .setTitle("Chave válida. Escolha o modelo:")
                        .setItems(flash.toTypedArray()) { _, i -> model.setText(flash[i]) }
                        .setNegativeButton("Fechar", null)
                        .show()
                }.onFailure { e ->
                    AlertDialog.Builder(this)
                        .setTitle("Erro")
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }
}
