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
import java.util.Locale

private const val SAMPLE = "Bom dia, Jorge. Estas são as notícias de hoje. " +
    "O Governo aprovou ontem um novo pacote de medidas para a habitação, que entra em vigor no início do próximo ano."

class SettingsActivity : Activity() {

    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var pause: EditText
    private lateinit var rate: EditText
    private lateinit var keep: EditText
    private lateinit var lead: CheckBox
    private lateinit var voiceLabel: TextView
    private var previewTts: TextToSpeech? = null

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

        root.addView(label("Voz"))
        voiceLabel = hint(currentVoiceText(prefs))
        root.addView(voiceLabel)
        root.addView(btn("Escolher voz (ouvir exemplos)") { chooseVoice() })
        root.addView(btn("Escolher motor de voz") { chooseEngine() })

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

    override fun onDestroy() {
        previewTts?.shutdown()
        super.onDestroy()
    }

    private fun currentVoiceText(prefs: Prefs): String {
        val v = if (prefs.voiceName.isBlank()) "automática" else prefs.voiceName
        val e = if (prefs.ttsEngine.isBlank()) "motor por omissão" else prefs.ttsEngine
        return "Voz: $v ($e)"
    }

    private fun withTts(engine: String, onReady: (TextToSpeech) -> Unit) {
        previewTts?.shutdown()
        var t: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { st ->
            val tt = t
            if (st == TextToSpeech.SUCCESS && tt != null) onReady(tt)
            else Toast.makeText(this, "Não foi possível iniciar o motor de voz", Toast.LENGTH_LONG).show()
        }
        t = if (engine.isBlank()) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
        previewTts = t
    }

    private fun regenerateAudio() {
        Repo.list(this).filter { it.isReady }.forEach { ProcessService.start(this, it.id, ProcessService.MODE_AUDIO) }
    }

    private fun chooseVoice() {
        val prefs = Prefs(this)
        withTts(prefs.ttsEngine) { t ->
            val voices = (t.voices ?: emptySet()).filter { it.locale.language == "pt" }
                .sortedWith(compareBy({ it.locale.country != "PT" }, { it.isNetworkConnectionRequired }, { it.name }))
            if (voices.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Sem vozes portuguesas")
                    .setMessage("Este motor de voz não tem vozes em português. Instale a voz \"Português (Portugal)\" ou escolha outro motor.")
                    .setPositiveButton("Instalar vozes") { _, _ ->
                        try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
                return@withTts
            }
            var n = 0
            val labels = voices.map { v ->
                n++
                val country = if (v.locale.country == "PT") "Portugal" else v.locale.getDisplayCountry(Locale.forLanguageTag("pt-PT"))
                val net = if (v.isNetworkConnectionRequired) " · com internet" else ""
                "$country · voz $n$net\n${v.name}"
            }.toTypedArray()
            var selected = voices.indexOfFirst { it.name == prefs.voiceName }
            val sampleRate = rate.text.toString().replace(',', '.').toFloatOrNull() ?: prefs.speechRate
            AlertDialog.Builder(this)
                .setTitle("Toque numa voz para ouvir")
                .setSingleChoiceItems(labels, selected) { _, w ->
                    selected = w
                    t.voice = voices[w]
                    t.setSpeechRate(sampleRate)
                    t.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, null, "amostra")
                }
                .setPositiveButton("Usar esta voz") { _, _ ->
                    if (selected >= 0) {
                        prefs.voiceName = voices[selected].name
                        voiceLabel.text = currentVoiceText(prefs)
                        regenerateAudio()
                        Toast.makeText(this, "Voz escolhida. Os títulos vão ser refeitos com a nova voz.", Toast.LENGTH_LONG).show()
                    }
                }
                .setNeutralButton("Automática") { _, _ ->
                    prefs.voiceName = ""
                    voiceLabel.text = currentVoiceText(prefs)
                    regenerateAudio()
                }
                .setNegativeButton("Cancelar", null)
                .setOnDismissListener { t.stop() }
                .show()
        }
    }

    private fun chooseEngine() {
        val prefs = Prefs(this)
        withTts(prefs.ttsEngine) { t ->
            val engines = t.engines ?: emptyList()
            if (engines.isEmpty()) {
                Toast.makeText(this, "Não há motores de voz instalados", Toast.LENGTH_LONG).show()
                return@withTts
            }
            val labels = engines.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Motor de voz")
                .setItems(labels) { _, w ->
                    prefs.ttsEngine = engines[w].name
                    prefs.voiceName = ""
                    voiceLabel.text = currentVoiceText(prefs)
                    chooseVoice()
                }
                .setNegativeButton("Fechar", null)
                .show()
        }
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
