package io.github.mrdadado.doukeeper

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.Kind
import io.github.mrdadado.doukeeper.parse.Aweme
import io.github.mrdadado.doukeeper.parse.AwemeParser
import io.github.mrdadado.doukeeper.parse.DetailCapture
import io.github.mrdadado.doukeeper.parse.ShortLinkResolver
import io.github.mrdadado.doukeeper.share.FileNames
import io.github.mrdadado.doukeeper.share.ShareTextParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 驗證版：只為了回答「在手機上能不能穩定下載」（docs/SPEC.md §10 Phase 0）。
 *
 * 刻意把 WebView 顯示出來而不是藏起來：驗證期間要看得到抖音頁面到底載入了什麼，
 * 失敗時才知道是驗證碼、導到別支影片、還是根本沒載入。正式版再藏起來。
 */
class MainActivity : ComponentActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val shortLinks = ShortLinkResolver(http)
    private lateinit var downloader: MediaStoreDownloader
    private lateinit var webView: WebView
    private var capture: DetailCapture? = null

    private val log = mutableStateListOf<String>()
    private var input by mutableStateOf("")
    private var current by mutableStateOf<Aweme?>(null)
    private var busy by mutableStateOf(false)
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = MediaStoreDownloader(http, contentResolver)
        webView = WebView(this)
        capture = DetailCapture(webView) { msg -> runOnUiThread { log += "  · $msg" } }.takeIf { it.install() }
        if (capture == null) log += "✗ 這台手機的 WebView 太舊，不支援 document-start 腳本"

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        input = text
        start(text)
    }

    private fun start(text: String) {
        val capture = capture ?: return
        job?.cancel()
        log.clear()
        current = null
        job = lifecycleScope.launch {
            busy = true
            try {
                val url = ShareTextParser.extractUrl(text)
                    ?: return@launch run { log += "✗ 這段分享內容裡沒有抖音連結" }
                log += "連結：$url"

                val t0 = SystemClock.elapsedRealtime()
                val id = runCatching { shortLinks.resolveAwemeId(url) }
                    .onFailure { log += "✗ 短網址導向失敗：${it.message}" }
                    .getOrNull() ?: return@launch run { log += "✗ 導向後的網址裡找不到作品 ID" }
                log += "作品 ID：$id（${SystemClock.elapsedRealtime() - t0}ms）"

                var aweme: Aweme? = null
                for (attempt in 1..2) {
                    val t1 = SystemClock.elapsedRealtime()
                    val json = capture.capture(id) { candidate ->
                        when (val r = AwemeParser.parse(candidate, id)) {
                            is AwemeParser.Result.Ok -> { aweme = r.aweme; true }
                            is AwemeParser.Result.WrongItem -> { log += "  略過別支作品的資料（${r.got}）"; false }
                            is AwemeParser.Result.Invalid -> { log += "  無效回應：${r.reason}"; false }
                        }
                    }
                    val ms = SystemClock.elapsedRealtime() - t1
                    if (json != null) { log += "取得資料（第 $attempt 次，${ms}ms）"; break }
                    log += "✗ 第 $attempt 次逾時（${ms}ms）"
                }
                val a = aweme ?: return@launch run { log += "✗ 拿不到作品資料" }
                current = a
                log += "「${a.title.take(30)}」— ${a.author}，${a.durationMs / 1000}s"
                log += "無浮水印 ${a.videoNoWatermark.size} 個網址、有浮水印 ${a.videoWatermark.size} 個、" +
                    "配樂 ${a.musicUrls.size} 個、圖片 ${a.images.size} 張"

                // 驗證版：解析完自動下載預設內容，模擬正式版「直接用預設下載」的行為。
                if (a.isImagePost) downloadImages(a) else download(a.videoNoWatermark, Kind.VIDEO, FileNames.video(a, watermark = false))
            } finally {
                busy = false
            }
        }
    }

    private suspend fun download(urls: List<String>, kind: Kind, name: String) {
        if (urls.isEmpty()) { log += "✗ $name：沒有網址"; return }
        val t0 = SystemClock.elapsedRealtime()
        val index = log.size
        log += "下載 $name…"
        runCatching {
            downloader.download(urls, kind, name) { done, total ->
                val pct = if (total > 0) " ${done * 100 / total}%" else ""
                runOnUiThread { log[index] = "下載 $name… ${done / 1024}KB$pct" }
            }
        }.onSuccess {
            log[index] = "✓ $name（${SystemClock.elapsedRealtime() - t0}ms）"
        }.onFailure {
            log[index] = "✗ $name：${it.message}"
        }
    }

    private suspend fun downloadImages(a: Aweme) {
        a.images.forEachIndexed { i, urls -> download(urls, Kind.IMAGE, FileNames.image(a, i)) }
    }

    private fun startExtra(block: suspend () -> Unit) {
        job = lifecycleScope.launch { busy = true; try { block() } finally { busy = false } }
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("貼上抖音分享文字") }, maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { start(input) }, enabled = !busy && capture != null) { Text("解析並下載") }
            }
            current?.let { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!a.isImagePost) {
                        Button(onClick = { startExtra { download(a.videoWatermark, Kind.VIDEO, FileNames.video(a, watermark = true)) } }, enabled = !busy) { Text("有浮水印") }
                    }
                    Button(onClick = { startExtra { download(a.musicUrls, Kind.AUDIO, FileNames.music(a)) } }, enabled = !busy) { Text("配樂") }
                }
            }
            // 出現人機驗證時要讓使用者自己滑，所以 WebView 佔大部分畫面。
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
            LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
                items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
