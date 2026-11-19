package pt.jorge.jornaisvoz

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Abre uma página num navegador invisível (WebView), espera pelos redirecionamentos
 * (ex.: links do Google Notícias) e devolve o endereço final e o texto visível.
 */
object PageFetcher {

    class Page(val url: String, val text: String)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(ctx: Context, url: String, onStatus: (String) -> Unit): Page =
        withContext(Dispatchers.Main) {
            val wv = WebView(ctx.applicationContext)
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.loadsImagesAutomatically = false
                CookieManager.getInstance().setAcceptCookie(true)
                wv.layout(0, 0, 1080, 2400)
                var lastFinish = 0L
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        lastFinish = System.currentTimeMillis()
                    }
                }
                wv.loadUrl(url)
                val start = System.currentTimeMillis()
                // Espera até a página estar carregada e estável (máx. 40 s).
                while (System.currentTimeMillis() - start < 40_000) {
                    delay(500)
                    val host = android.net.Uri.parse(wv.url ?: "").host ?: ""
                    val onGoogle = host.contains("news.google.") || host.contains("share.google") ||
                        host.contains("consent.google")
                    if (lastFinish > 0 && !onGoogle && System.currentTimeMillis() - lastFinish > 2500) break
                    if (onGoogle) onStatus("a abrir o link do Google Notícias…")
                    if (host.contains("consent.google") && lastFinish > 0) {
                        // Página de cookies da Google (UE): carrega em "Rejeitar tudo".
                        wv.evaluateJavascript(
                            "(function(){var b=[].slice.call(document.querySelectorAll('button,input[type=submit]'));" +
                                "var r=b.find(function(x){return /rejeitar tudo|reject all|recusar tudo/i.test(x.innerText||x.value||'');});" +
                                "if(r){r.click();}})()", null
                        )
                        lastFinish = 0
                    }
                }
                val js = "(function(){return document.title + '\\n\\n' + (document.body ? document.body.innerText : '');})()"
                val raw = suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript(js) { v -> cont.resume(v ?: "") }
                }
                val text = try {
                    JSONArray("[$raw]").getString(0)
                } catch (e: Exception) {
                    raw
                }
                Page(wv.url ?: url, text)
            } finally {
                wv.stopLoading()
                wv.destroy()
            }
        }
}
