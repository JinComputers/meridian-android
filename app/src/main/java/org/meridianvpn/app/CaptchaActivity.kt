package org.meridianvpn.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URLDecoder

/**
 * Экран капчи: обычный видимый WebView, человек решает сам.
 *
 * Автоматического решения здесь НЕТ и не будет в этом заходе. В
 * апстриме на это уходит полторы тысячи строк с proof-of-work и
 * распознаванием картинки, и всё это ломается при первой смене
 * оформления. Капчу мы пока не видели ни разу — сначала надо узнать её
 * частоту, а уже потом решать, стоит ли она автоматики.
 *
 * КАК ЛОВИМ ТОКЕН — И ЧЕГО ЗДЕСЬ НЕ ХВАТАЕТ.
 *
 * В апстриме перехватывается ответ captchaNotRobot.check. Точного
 * способа у нас нет, живой капчи мы не видели, поэтому ловим тремя
 * сетями сразу и логируем всё, что происходит:
 *
 *  1. переходы по адресам — success_token часто приезжает параметром;
 *  2. загруженные подзапросы — тот же параметр, но в запросе к API;
 *  3. содержимое страницы — на случай, если токен просто напечатан.
 *
 * Первая живая капча покажет, какая из сетей сработала, и лишние можно
 * будет убрать. Пока лучше три, чем ни одной.
 */
class CaptchaActivity : Activity() {

    private var delivered = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = CaptchaGate.pendingUri
        if (uri.isNullOrEmpty()) {
            TunnelLog.add("капча: экран открыт, но ждать нечего — закрываюсь")
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(
            TextView(this).apply {
                text = "VK просит подтвердить, что вы не робот.\n" +
                    "Решите проверку — окно закроется само."
                setPadding(32, 32, 32, 16)
                setTextColor(Color.BLACK)
            }
        )

        val web = WebView(this)
        web.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = null // пусть будет родной у WebView

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                catchToken(request?.url?.toString(), "переход")
                return false
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                catchToken(url, "подзапрос")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                catchToken(url, "страница загружена")
                // Третья сеть: токен мог быть напечатан прямо в теле.
                view?.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})();"
                ) { raw -> catchToken(raw, "текст страницы") }
            }
        }

        root.addView(web)
        setContentView(root)

        TunnelLog.add("капча: открываю экран")
        web.loadUrl(uri)
    }

    /**
     * Достаёт success_token откуда угодно и отдаёт движку.
     *
     * Имя параметра ищется и как `success_token=`, и как `"success_token":`
     * — в адресе и в теле оно выглядит по-разному.
     */
    private fun catchToken(text: String?, whence: String) {
        if (delivered || text.isNullOrEmpty()) return

        val token = extract(text) ?: return
        delivered = true
        // Сам токен в лог НЕ пишем: это ключ, пусть и одноразовый.
        TunnelLog.add("капча: поймал success_token ($whence, ${token.length} символов)")
        CaptchaGate.deliver(token)
        finish()
    }

    private fun extract(text: String): String? {
        for (marker in listOf("success_token=", "\"success_token\":\"", "success_token\":\"")) {
            val at = text.indexOf(marker)
            if (at < 0) continue
            var rest = text.substring(at + marker.length)
            val end = rest.indexOfFirst { it == '&' || it == '"' || it == '\'' || it == ' ' }
            if (end >= 0) rest = rest.substring(0, end)
            rest = rest.trim()
            if (rest.length >= 8) {
                return try {
                    URLDecoder.decode(rest, "UTF-8")
                } catch (e: Throwable) {
                    rest
                }
            }
        }
        return null
    }

    override fun onDestroy() {
        // Закрыли, не решив — движок должен узнать об этом сразу, а не
        // ждать свои три минуты впустую. Хеш при этом НЕ помечается:
        // капча не свойство ссылки.
        if (!delivered) {
            TunnelLog.add("капча: экран закрыт без решения")
            CaptchaGate.deliver("")
        }
        super.onDestroy()
    }
}
