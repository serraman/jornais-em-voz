package pt.jorge.jornaisvoz

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PageArticle(
    val type: String,
    val section: String,
    val title: String,
    val lead: String,
    val text: String,
    val continuesPrevious: Boolean,
    val continuesNext: Boolean,
)

class PageResult(val paperName: String, val articles: List<PageArticle>)

/** Erro que não vale a pena repetir (chave inválida, modelo inexistente, limite diário). */
class FatalError(msg: String) : Exception(msg)

private class BadRequest(msg: String) : Exception(msg)

class GeminiClient(private val apiKey: String, private val model: String) {

    /** Mensagens de estado (esperas, novas tentativas) para mostrar no ecrã. */
    @Volatile var onStatus: ((String) -> Unit)? = null

    /** Pede ao modelo para "pensar" pouco (muito mais rápido). Desliga-se se o modelo não aceitar. */
    @Volatile private var useThinkingLevel = true

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    /** Bloqueante: chamar fora da thread principal. */
    fun analyzePage(jpeg: ByteArray, page: Int, total: Int): PageResult {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val text = try {
            try {
                generate(b64, page, total, withSchema = true)
            } catch (e: BadRequest) {
                if (useThinkingLevel && e.message.orEmpty().contains("think", true)) {
                    useThinkingLevel = false
                    generate(b64, page, total, withSchema = true)
                } else throw e
            }
        } catch (e: BadRequest) {
            // Alguns modelos não aceitam o esquema: tenta só com JSON livre.
            try {
                generate(b64, page, total, withSchema = false)
            } catch (e2: BadRequest) {
                throw FatalError("O Gemini recusou o pedido: ${e2.message}")
            }
        }
        return parse(text)
    }

    fun listModels(): List<String> {
        val conn = open("$base/models?pageSize=200", "GET")
        val code = conn.responseCode
        val body = readBody(conn, code)
        if (code != 200) throw FatalError(describeError(code, body))
        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent")) continue
            out.add(m.optString("name").removePrefix("models/"))
        }
        return out.sorted()
    }

    private fun generate(b64: String, page: Int, total: Int, withSchema: Boolean): String {
        val genConfig = JSONObject().apply {
            put("responseMimeType", "application/json")
            put("maxOutputTokens", 32768)
            if (withSchema) put("responseSchema", schema())
            if (useThinkingLevel) put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg"); put("data", b64)
                    }))
                    .put(JSONObject().put("text", prompt(page, total))))
            }))
            put("generationConfig", genConfig)
        }
        val resp = postWithRetry("$base/models/$model:generateContent", body.toString(), page)
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IOException("Resposta vazia do Gemini")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    private fun postWithRetry(url: String, body: String, page: Int): JSONObject {
        var lastError = "erro desconhecido"
        var timeouts = 0
        for (attempt in 0 until 6) {
            val conn = open(url, "POST")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = readBody(conn, code)
                when {
                    code == 200 -> return JSONObject(text)
                    code == 429 -> {
                        if (text.contains("PerDay", true) || text.contains("per day", true)) {
                            throw FatalError("Atingiu o limite diário gratuito do modelo $model. Tente amanhã ou escolha outro modelo nas Definições.")
                        }
                        val secs = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(text)
                            ?.groupValues?.get(1)?.toLongOrNull() ?: (30L * (attempt + 1))
                        lastError = "limite por minuto"
                        onStatus?.invoke("pág. $page: limite por minuto do Gemini, a aguardar ${secs + 2} s")
                        Thread.sleep((secs + 2) * 1000)
                    }
                    code >= 500 -> {
                        lastError = "serviço do Gemini indisponível ($code)"
                        onStatus?.invoke("pág. $page: Gemini ocupado ($code), nova tentativa")
                        Thread.sleep(10_000L * (attempt + 1))
                    }
                    code == 400 -> {
                        if (text.contains("API key", true) || text.contains("API_KEY", true)) {
                            throw FatalError("A chave da API Gemini não é válida. Verifique-a nas Definições.")
                        }
                        throw BadRequest(shortMessage(text))
                    }
                    else -> throw FatalError(describeError(code, text))
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = "o Gemini não respondeu em 3 minutos"
                onStatus?.invoke("pág. $page: o Gemini demorou demasiado, nova tentativa")
                if (++timeouts >= 2) break
            } catch (e: IOException) {
                lastError = e.message ?: "erro de rede"
                onStatus?.invoke("pág. $page: erro de rede ($lastError), nova tentativa")
                Thread.sleep(5_000L * (attempt + 1))
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Falhou após várias tentativas: $lastError")
    }

    private fun describeError(code: Int, text: String): String = when (code) {
        401, 403 -> "A chave da API Gemini não é válida ou não tem permissão. Verifique-a nas Definições."
        404 -> "O modelo '$model' não existe. Escolha outro nas Definições (botão Testar)."
        else -> "Erro $code do Gemini: ${shortMessage(text)}"
    }

    private fun shortMessage(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("message")?.take(300) ?: text.take(300)
    } catch (e: Exception) {
        text.take(300)
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("x-goog-api-key", apiKey)
        return conn
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun prompt(page: Int, total: Int) = """
Esta imagem é a página $page de $total de um jornal português.
Extrai TODAS as notícias e artigos de texto desta página. Transcreve o texto INTEGRAL de cada artigo, palavra a palavra, sem resumir nem cortar. Junta as palavras partidas por hífen no fim da linha. Segue a ordem de leitura correcta das colunas.
Ignora: publicidade, cabeçalhos e rodapés da página, legendas de fotografias, assinaturas de autores, programação de TV, meteorologia, passatempos, tabelas de resultados e índices.
Uma caixa lateral com título próprio é um artigo separado.
Para cada artigo devolve:
- "tipo": "noticia", "opiniao", "breve" ou "chamada" (chamada = destaque de primeira página ou índice que remete para outra página do jornal);
- "seccao": a secção do jornal (ex.: Sociedade, Política, Desporto), se estiver visível, senão "";
- "titulo": o título do artigo ("" se o texto é continuação da página anterior e não tem título);
- "entrada": o subtítulo ou parágrafo de entrada em destaque, se existir, senão "";
- "texto": o corpo integral do artigo (sem repetir o título nem a entrada);
- "continua_da_anterior": true se o texto começa a meio de uma frase, continuando um artigo da página anterior;
- "continua_na_seguinte": true se o texto acaba a meio e continua noutra página.
Indica também "nome_jornal" se o nome do jornal aparecer na página, senão "".
Responde só com JSON neste formato:
{"nome_jornal":"","artigos":[{"tipo":"noticia","seccao":"","titulo":"","entrada":"","texto":"","continua_da_anterior":false,"continua_na_seguinte":false}]}
""".trimIndent()

    private fun schema(): JSONObject {
        fun str() = JSONObject().put("type", "STRING")
        fun bool() = JSONObject().put("type", "BOOLEAN")
        val art = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("tipo", JSONObject().put("type", "STRING")
                    .put("enum", JSONArray(listOf("noticia", "opiniao", "breve", "chamada"))))
                put("seccao", str()); put("titulo", str()); put("entrada", str()); put("texto", str())
                put("continua_da_anterior", bool()); put("continua_na_seguinte", bool())
            })
            put("required", JSONArray(listOf("tipo", "titulo", "texto")))
        }
        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("nome_jornal", str())
                put("artigos", JSONObject().put("type", "ARRAY").put("items", art))
            })
            put("required", JSONArray(listOf("artigos")))
        }
    }

    private fun parse(raw: String): PageResult {
        var s = raw.trim()
        if (s.startsWith("```")) s = s.substringAfter('\n').substringBeforeLast("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Resposta do Gemini sem JSON")
        val o = JSONObject(s.substring(start, end + 1))
        val arr = o.optJSONArray("artigos") ?: JSONArray()
        val list = mutableListOf<PageArticle>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            list.add(
                PageArticle(
                    type = a.optString("tipo", "noticia"),
                    section = a.optString("seccao").trim(),
                    title = a.optString("titulo").trim(),
                    lead = a.optString("entrada").trim(),
                    text = a.optString("texto").trim(),
                    continuesPrevious = a.optBoolean("continua_da_anterior", false),
                    continuesNext = a.optBoolean("continua_na_seguinte", false),
                )
            )
        }
        return PageResult(o.optString("nome_jornal").trim(), list)
    }
}
