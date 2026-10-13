package io.github.mrdadado.doukeeper

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 唯一的畫面。以「現在是什麼狀態」為中心：最上面一張狀態卡，一眼看出待命、處理中、
 * 需要驗證、下載中、完成或失敗。
 *
 * 為什麼要這樣：上一版只有一串診斷文字，使用者看不出成功沒有，結果同一支影片按了三次、
 * 相簿裡多出 (1)、(2) 兩份重複檔（2026-10-13 手機實際發生）。診斷文字收進「詳細記錄」。
 */
class MainActivity : ComponentActivity() {

    /** 狀態卡顯示的狀態。一次只會是其中一種。 */
    sealed interface Phase {
        data object Idle : Phase
        data class Working(val step: String) : Phase
        /** 等太久還沒拿到資料——幾乎都是跳出了人機驗證，把網頁展開給使用者滑。 */
        data object NeedsVerification : Phase
        data class Downloading(val label: String, val done: Long, val total: Long) : Phase
        data class Done(val label: String, val uri: Uri, val mime: String) : Phase
        data class Failed(val reason: String) : Phase
    }

    /** 可下載的項目。按鈕上的字與完成後的打勾都靠這個。 */
    enum class Item(val label: String) {
        NO_WATERMARK("無浮水印影片"), WATERMARK("有浮水印影片"), MUSIC("配樂"), IMAGES("圖片"),
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val shortLinks = ShortLinkResolver(http)
    private lateinit var downloader: MediaStoreDownloader
    private lateinit var webView: WebView
    private var capture: DetailCapture? = null
    private lateinit var attempts: AttemptLog

    private var phase by mutableStateOf<Phase>(Phase.Idle)
    private var current by mutableStateOf<Aweme?>(null)
    private val downloaded = mutableStateMapOf<Item, Uri>()
    private var showWebView by mutableStateOf(false)
    private var showLog by mutableStateOf(false)
    private var input by mutableStateOf("")
    private var summary by mutableStateOf(AttemptLog.Summary(0, 0))
    private val log = mutableStateListOf<String>()
    private var job: Job? = null

    private val busy: Boolean
        get() = phase is Phase.Working || phase is Phase.NeedsVerification || phase is Phase.Downloading

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = MediaStoreDownloader(http, contentResolver)
        attempts = AttemptLog(filesDir)
        summary = attempts.summary()
        // 軟體繪製：Android 17 實機 上，硬體繪製的 WebView 放進 Compose 會讓**整個視窗**全黑
        //（不只 WebView 那一塊，按鈕和文字也看不到；版面與無障礙節點都正常，就是畫不出來）。
        // 模擬器 Android 14 沒有這個問題。拿掉 WebView 畫面立刻正常，改成軟體繪製也正常。
        webView = WebView(this).apply { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        capture = DetailCapture(webView) { msg -> runOnUiThread { log += "· $msg" } }.takeIf { it.install() }
        if (capture == null) phase = Phase.Failed("這台手機的 WebView 太舊，無法解析抖音頁面")

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

    /**
     * 拿到焦點時讀剪貼簿：抖音只能「複製連結」，這是主要入口（docs/SPEC.md B2）。
     *
     * 必須等 onWindowFocusChanged，不能在 onResume 讀：Android 10 起 App 要真的拿到焦點
     * 才讀得到剪貼簿，onResume 時還沒有，讀到的是空的。
     *
     * 同一個連結只自動處理一次，否則每次切回 App 都會重新下載一份。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || busy) return
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        val url = ShareTextParser.extractUrl(text) ?: return
        val prefs = getSharedPreferences("clipboard", MODE_PRIVATE)
        if (prefs.getString(LAST_AUTO_URL, null) == url) return
        prefs.edit().putString(LAST_AUTO_URL, url).apply()
        input = text
        start(text)
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
        downloaded.clear()
        job = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            var awemeId = "-"
            fun fail(reason: String, record: String = reason) {
                phase = Phase.Failed(reason)
                log += "✗ $reason"
                attempts.record(awemeId, false, record, SystemClock.elapsedRealtime() - started)
                summary = attempts.summary()
            }

            phase = Phase.Working("讀取連結")
            val url = ShareTextParser.extractUrl(text)
                ?: return@launch run { phase = Phase.Failed("這段文字裡沒有抖音連結") }
            log += "連結：$url"

            phase = Phase.Working("尋找作品")
            val id = runCatching { shortLinks.resolveAwemeId(url) }
                .onFailure { log += "短網址導向失敗：${it.message}" }
                .getOrNull() ?: return@launch fail("連不到抖音，請檢查網路", "短網址")
            awemeId = id
            log += "作品 ID：$id"

            phase = Phase.Working("取得作品資料")
            // 6 秒還沒拿到就展開網頁：實測正常情況 3–9 秒，超過多半是跳出了人機驗證。
            val revealer = launch {
                delay(6_000)
                showWebView = true
                phase = Phase.NeedsVerification
            }
            var aweme: Aweme? = null
            var wrongItems = 0
            for (attempt in 1..2) {
                val json = capture.capture(id) { candidate ->
                    when (val r = AwemeParser.parse(candidate, id)) {
                        is AwemeParser.Result.Ok -> { aweme = r.aweme; true }
                        is AwemeParser.Result.WrongItem -> { wrongItems++; log += "略過別支作品的資料（${r.got}）"; false }
                        is AwemeParser.Result.Invalid -> { log += "無效回應：${r.reason}"; false }
                    }
                }
                if (json != null) break
                log += "第 $attempt 次沒有拿到資料"
            }
            revealer.cancel()
            showWebView = false

            val a = aweme ?: return@launch fail(
                if (wrongItems > 0) "抖音把頁面導到了別支影片，請稍後再試" else "拿不到作品資料，請稍後再試",
                if (wrongItems > 0) "被導到別支作品×$wrongItems" else "逾時",
            )
            current = a
            log += "「${a.title.take(30)}」— ${a.author}，${a.durationMs / 1000}s"

            val ok = download(a, if (a.isImagePost) Item.IMAGES else Item.NO_WATERMARK)
            attempts.record(awemeId, ok, if (ok) "" else "下載失敗", SystemClock.elapsedRealtime() - started)
            summary = attempts.summary()
        }
    }

    private suspend fun download(a: Aweme, item: Item): Boolean {
        val targets: List<Triple<List<String>, Kind, String>> = when (item) {
            Item.NO_WATERMARK -> listOf(Triple(a.videoNoWatermark, Kind.VIDEO, FileNames.video(a, watermark = false)))
            Item.WATERMARK -> listOf(Triple(a.videoWatermark, Kind.VIDEO, FileNames.video(a, watermark = true)))
            Item.MUSIC -> listOf(Triple(a.musicUrls, Kind.AUDIO, FileNames.music(a)))
            Item.IMAGES -> a.images.mapIndexed { i, urls -> Triple(urls, Kind.IMAGE, FileNames.image(a, i)) }
        }
        if (targets.isEmpty() || targets.any { it.first.isEmpty() }) {
            phase = Phase.Failed("這個作品沒有「${item.label}」")
            return false
        }
        var last: Uri? = null
        targets.forEachIndexed { i, (urls, kind, name) ->
            val label = if (targets.size > 1) "${item.label} ${i + 1}/${targets.size}" else item.label
            phase = Phase.Downloading(label, 0, -1)
            var lastPct = -1L
            val result = runCatching {
                downloader.download(urls, kind, name) { done, total ->
                    // 每 64KB 回報一次，太頻繁；只在百分比（或未知大小時每 1MB）變動時更新畫面。
                    val pct = if (total > 0) done * 100 / total else done / (1024 * 1024)
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread { phase = Phase.Downloading(label, done, total) }
                    }
                }
            }
            val uri = result.getOrElse {
                log += "✗ $name：${it.message}"
                phase = Phase.Failed("下載失敗：${it.message}")
                return false
            }
            log += "✓ $name"
            last = uri
        }
        val uri = last!!
        downloaded[item] = uri
        phase = Phase.Done(item.label, uri, targets.last().second.mime)
        return true
    }

    private fun downloadExtra(item: Item) {
        val a = current ?: return
        job = lifecycleScope.launch { download(a, item) }
    }

    private fun open(uri: Uri, mime: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private fun share(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    companion object {
        private const val LAST_AUTO_URL = "last_auto_url"
        private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    // ---------------------------------------------------------------- 畫面

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("抖音下載", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer()
                if (summary.total > 0) {
                    Text(
                        "最近 ${summary.total} 次成功 ${summary.ok} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            StatusCard()

            current?.let { a -> WorkCard(a) }

            // 驗證時網頁要盡量大；平常縮成 1dp 但保持掛在畫面上——拔掉的話頁面裡的 JS 可能停擺。
            AndroidView(
                factory = { webView },
                modifier = if (showWebView) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(1.dp),
            )

            if (!showWebView) {
                ManualInput()
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "▾ 詳細記錄" else "▸ 詳細記錄")
                }
                if (showLog) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.Spacer() =
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

    @Composable
    private fun StatusCard() {
        val dark = isSystemInDarkTheme()
        val scheme = MaterialTheme.colorScheme
        val (container, content) = when (phase) {
            is Phase.Done -> if (dark) Color(0xFF1E4620) to Color(0xFFC8F5C8) else Color(0xFFDDF4DD) to Color(0xFF1B5E20)
            is Phase.Failed -> scheme.errorContainer to scheme.onErrorContainer
            Phase.NeedsVerification -> scheme.tertiaryContainer to scheme.onTertiaryContainer
            is Phase.Working, is Phase.Downloading -> scheme.secondaryContainer to scheme.onSecondaryContainer
            Phase.Idle -> scheme.surfaceVariant to scheme.onSurfaceVariant
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val p = phase) {
                    Phase.Idle -> {
                        Headline("📋", "等待連結")
                        Text("在抖音按「分享 → 複製連結」，再回到這裡就會自動開始。\n也可以下拉通知欄點「抖音下載」磚塊。")
                    }
                    is Phase.Working -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = content, strokeWidth = 3.dp)
                            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                            Text("${p.step}…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Phase.NeedsVerification -> {
                        Headline("👇", "請完成下方驗證")
                        Text("抖音要求確認你不是機器人。在下方網頁完成驗證後會自動繼續；如果沒有看到驗證，請再等一下。")
                    }
                    is Phase.Downloading -> {
                        Headline("⬇", "下載${p.label}")
                        if (p.total > 0) {
                            LinearProgressIndicator(
                                progress = { p.done.toFloat() / p.total },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = content,
                            )
                            Text("${p.done * 100 / p.total}%　${mb(p.done)} / ${mb(p.total)}")
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = content)
                            Text(mb(p.done))
                        }
                    }
                    is Phase.Done -> {
                        Headline("✓", "已儲存${p.label}")
                        Text("相簿 › ${if (p.mime.startsWith("audio")) "Music" else if (p.mime.startsWith("image")) "Pictures" else "Movies"} › ${MediaStoreDownloader.FOLDER}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { open(p.uri, p.mime) }) { Text("開啟") }
                            OutlinedButton(onClick = { share(p.uri, p.mime) }) { Text("分享") }
                        }
                    }
                    is Phase.Failed -> {
                        Headline("✗", "失敗")
                        Text(p.reason)
                        if (input.isNotBlank()) {
                            Button(onClick = { start(input) }) { Text("重試") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Headline(icon: String, text: String) {
        Text("$icon  $text", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun WorkCard(a: Aweme) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(a.title.ifBlank { "（沒有標題）" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "@${a.author}" + if (a.durationMs > 0) "　${a.durationMs / 1000 / 60}:%02d".format(a.durationMs / 1000 % 60) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val items = if (a.isImagePost) listOf(Item.IMAGES, Item.MUSIC) else listOf(Item.NO_WATERMARK, Item.WATERMARK, Item.MUSIC)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        val done = item in downloaded
                        FilledTonalButton(onClick = { downloadExtra(item) }, enabled = !busy) {
                            Text((if (done) "✓ " else "") + item.label.removeSuffix("影片"))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ManualInput() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), label = { Text("或手動貼上連結") }, singleLine = true,
            )
            OutlinedButton(onClick = { start(input) }, enabled = !busy && capture != null && input.isNotBlank()) {
                Text("下載")
            }
        }
    }
}
