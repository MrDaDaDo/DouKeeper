package io.github.mrdadado.doukeeper.parse

import android.annotation.SuppressLint
import android.net.Uri
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.mrdadado.doukeeper.R
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 讓 WebView 執行抖音網頁版，攔截頁面自己發出的 `aweme/detail` 回應（docs/SPEC.md §5.B）。
 *
 * 為什麼要這麼繞：純 HTTP 請求只會拿到反爬蟲驗證頁，詳情 API 的簽章又是抖音的 JS 算的。
 * 讓抖音自己的 JS 在真的瀏覽器引擎裡跑，簽章、cookie 都不必我們處理；
 * 抖音改簽章演算法時，這條路也不會跟著壞。
 *
 * 必須在主執行緒建立與呼叫。
 */
class DetailCapture(
    private val webView: WebView,
    /** 驗證期間的診斷訊息：頁面載入到哪、攔到哪些 API、JS 錯誤。正式版拿掉。 */
    private val onEvent: (String) -> Unit = {},
) {

    /**
     * 頁面送回來的每一份 detail JSON。用 Channel 而不是一次性的 Deferred：
     * 頁面可能連續送兩份（先別支、再正確那支），兩份之間不能有漏接的空檔。
     */
    private val received = Channel<String>(Channel.UNLIMITED)

    @SuppressLint("SetJavaScriptEnabled")
    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) return false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 必須是桌面版 UA：手機 UA 會被導到 m.douyin.com/share/video/{id}，
            // 那個頁面不發 detail 請求，什麼都拿不到（§5.B 實測）。
            userAgentString = DESKTOP_UA
            mediaPlaybackRequiresUserGesture = true
            // 桌面版頁面在手機寬度下會超出畫面（驗證框被擠到右邊看不到）。
            // 縮放到整頁可見，並允許手勢縮放，使用者才能操作驗證。
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onEvent(view.context.getString(R.string.log_page_started, url.take(90)))
            }
            override fun onPageFinished(view: WebView, url: String) {
                onEvent(view.context.getString(R.string.log_page_finished, url.take(90), view.title))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onEvent(view.context.getString(R.string.log_page_error, error.errorCode, error.description))
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) onEvent(view.context.getString(R.string.log_page_http, response.statusCode))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                // chrome-extension:// 那一大串是抖音的指紋腳本在探測擴充套件，WebView 必然失敗，不是問題。
                val text = m.message()
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && "chrome-extension://" !in text) {
                    onEvent(webView.context.getString(R.string.log_js_error, text.take(120)))
                }
                return true
            }
        }

        // 只接受抖音網域送來的訊息。用 WebMessageListener 而不是 addJavascriptInterface：
        // 後者會把原生物件暴露給頁面裡的所有 frame（包括廣告 iframe）。
        WebViewCompat.addWebMessageListener(
            webView, BRIDGE, ALLOWED_ORIGINS,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView, message: WebMessageCompat, sourceOrigin: Uri,
                    isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy,
                ) {
                    val data = message.data ?: return
                    if (data.startsWith(HOOK_PREFIX)) onEvent(view.context.getString(R.string.log_hook_installed, data.removePrefix(HOOK_PREFIX)))
                    else if (data.startsWith(DEBUG_PREFIX)) onEvent(data.removePrefix(DEBUG_PREFIX))
                    else received.trySend(data)
                }
            },
        )
        // document-start：一定要在抖音自己的 JS 拿到 fetch 參照之前包好，晚了就攔不到。
        WebViewCompat.addDocumentStartJavaScript(webView, HOOK_JS, ALLOWED_ORIGINS)
        return true
    }

    /**
     * 載入作品頁並等 detail 回應。頁面可能先送出別支作品的 detail（被導到推薦影片），
     * 所以收到的每一份都交給 [accept] 判斷，回傳 true 才算拿到。
     */
    suspend fun capture(awemeId: String, timeoutMs: Long = 120_000, accept: (String) -> Boolean): String? {
        // 清掉上一次殘留的（例如上一次逾時後才到的回應）。
        while (received.tryReceive().isSuccess) Unit
        return withTimeoutOrNull(timeoutMs) {
            webView.loadUrl("https://www.douyin.com/video/$awemeId")
            var json: String
            do {
                json = received.receive()
            } while (!accept(json))
            json
        }.also {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
    }

    companion object {
        private const val BRIDGE = "DyBridge"
        private const val DEBUG_PREFIX = "@@"
        private const val HOOK_PREFIX = "@@hook:"
        private val ALLOWED_ORIGINS = setOf("https://www.douyin.com", "https://*.douyin.com")
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

        // 同時包 fetch 與 XHR：不確定抖音用哪一個，而且可能改。
        // XHR 的 responseType 可能是 json，那時讀 responseText 會丟例外。
        private val HOOK_JS = """
            (function () {
              if (window.__dyHooked) return;
              window.__dyHooked = true;
              var TARGET = '/aweme/v1/web/aweme/detail';
              function send(t) { try { if (t) DyBridge.postMessage(String(t)); } catch (e) {} }
              function debug(t) { try { DyBridge.postMessage('@@' + t); } catch (e) {} }
              function seen(kind, u) {
                if (u.indexOf('/aweme/v1/web/') >= 0) debug(kind + ' ' + u.split('?')[0].replace(/^https?:\/\/[^/]+/, ''));
              }
              debug('hook:' + location.host + (window.top === window ? '' : ' (iframe)'));

              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function (input, init) {
                  var p = origFetch.apply(this, arguments);
                  try {
                    var u = typeof input === 'string' ? input : (input && input.url) || String(input);
                    seen('fetch', u);
                    if (u.indexOf(TARGET) >= 0) {
                      p.then(function (r) { return r.clone().text(); }).then(send).catch(function () {});
                    }
                  } catch (e) {}
                  return p;
                };
              }

              var origOpen = XMLHttpRequest.prototype.open;
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (m, u) {
                this.__dyUrl = String(u);
                seen('xhr', this.__dyUrl);
                return origOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function () {
                if (this.__dyUrl && this.__dyUrl.indexOf(TARGET) >= 0) {
                  this.addEventListener('load', function () {
                    try {
                      var rt = this.responseType;
                      if (rt === '' || rt === 'text') send(this.responseText);
                      else if (rt === 'json') send(JSON.stringify(this.response));
                    } catch (e) {}
                  });
                }
                return origSend.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
