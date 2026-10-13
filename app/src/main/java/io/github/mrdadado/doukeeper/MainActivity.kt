package io.github.mrdadado.doukeeper

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.DownloadException
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.Kind
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.Reason
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
        data class Working(@StringRes val step: Int) : Phase
        /** 等太久還沒拿到資料——幾乎都是跳出了人機驗證，把網頁展開給使用者滑。 */
        data object NeedsVerification : Phase
        data class Downloading(val label: String, val done: Long, val total: Long) : Phase
        data class Done(val label: String, val uri: Uri, val mime: String) : Phase
        data class Failed(val reason: String) : Phase
    }

    /** 可下載的項目。按鈕上的字與完成後的打勾都靠這個。 */
    enum class Item(@StringRes val label: Int, @StringRes val short: Int) {
        NO_WATERMARK(R.string.item_no_watermark, R.string.item_no_watermark_short),
        WATERMARK(R.string.item_watermark, R.string.item_watermark_short),
        MUSIC(R.string.item_music, R.string.item_music_short),
        IMAGES(R.string.item_images, R.string.item_images_short),
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
    private lateinit var settings: Settings
    private var showSettings by mutableStateOf(false)
    private var autoStart by mutableStateOf(true)
    private var defaultItems by mutableStateOf(setOf(Item.NO_WATERMARK))

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
        settings = Settings(this)
        autoStart = settings.autoStart
        defaultItems = settings.defaultItems
        summary = attempts.summary()
        // 軟體繪製：Android 17 實機 上，硬體繪製的 WebView 放進 Compose 會讓**整個視窗**全黑
        //（不只 WebView 那一塊，按鈕和文字也看不到；版面與無障礙節點都正常，就是畫不出來）。
        // 模擬器 Android 14 沒有這個問題。拿掉 WebView 畫面立刻正常，改成軟體繪製也正常。
        webView = WebView(this).apply { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        capture = DetailCapture(webView) { msg -> runOnUiThread { log += "· $msg" } }.takeIf { it.install() }
        if (capture == null) phase = Phase.Failed(getString(R.string.error_webview_too_old))

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
        // 關掉自動開始時只填進輸入框，讓使用者自己按下載。
        if (autoStart) start(text)
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

            phase = Phase.Working(R.string.step_reading_link)
            val url = ShareTextParser.extractUrl(text)
                ?: return@launch run { phase = Phase.Failed(getString(R.string.error_no_link)) }
            log += getString(R.string.log_link, url)

            phase = Phase.Working(R.string.step_finding_video)
            val id = runCatching { shortLinks.resolveAwemeId(url) }
                .onFailure { log += getString(R.string.log_short_link_failed, it.message) }
                .getOrNull() ?: return@launch fail(getString(R.string.error_network), "short-link")
            awemeId = id
            log += getString(R.string.log_video_id, id)

            phase = Phase.Working(R.string.step_fetching_details)
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
                        is AwemeParser.Result.WrongItem -> { wrongItems++; log += getString(R.string.log_wrong_item, r.got); false }
                        is AwemeParser.Result.Invalid -> { log += getString(R.string.log_invalid, r.reason); false }
                    }
                }
                if (json != null) break
                log += getString(R.string.log_attempt_failed, attempt)
            }
            revealer.cancel()
            showWebView = false

            val a = aweme ?: return@launch fail(
                getString(if (wrongItems > 0) R.string.error_redirected else R.string.error_no_data),
                // attempts.tsv 是給開發分析用的，固定英文，跟著語言變的話不好統計。
                if (wrongItems > 0) "redirected-to-other-video x$wrongItems" else "timeout",
            )
            current = a
            log += getString(R.string.log_video, a.title.take(30), a.author, a.durationMs / 1000)

            // 圖文作品沒有影片：一律下載圖片，再加上有勾的配樂。
            val items = if (a.isImagePost) listOfNotNull(Item.IMAGES, Item.MUSIC.takeIf { it in defaultItems })
                else Item.entries.filter { it != Item.IMAGES && it in defaultItems }
            var ok = true
            for (item in items) ok = download(a, item) && ok
            attempts.record(awemeId, ok, if (ok) "" else "download-failed", SystemClock.elapsedRealtime() - started)
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
            phase = Phase.Failed(getString(R.string.error_item_missing, getString(item.label)))
            return false
        }
        var last: Uri? = null
        targets.forEachIndexed { i, (urls, kind, name) ->
            val label = if (targets.size > 1) getString(R.string.part_of, getString(item.label), i + 1, targets.size)
                else getString(item.label)
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
                val why = describe(it)
                log += getString(R.string.log_item_failed, name, why)
                phase = Phase.Failed(getString(R.string.error_download, why))
                return false
            }
            log += getString(R.string.log_item_ok, name)
            last = uri
        }
        val uri = last!!
        downloaded[item] = uri
        phase = Phase.Done(getString(item.label), uri, targets.last().second.mime)
        return true
    }

    private fun describe(e: Throwable): String = when (e) {
        is DownloadException -> when (e.reason) {
            Reason.NO_URLS -> getString(R.string.error_no_urls)
            Reason.HTTP -> getString(R.string.error_http, e.code)
            Reason.EMPTY_BODY -> getString(R.string.error_empty_body)
            Reason.MEDIA_STORE -> getString(R.string.error_media_store)
            Reason.INCOMPLETE -> getString(R.string.error_incomplete, mb(e.done), mb(e.total))
        }
        // 網路層的例外（逾時、DNS…）訊息本來就是英文的技術描述，原樣顯示。
        else -> e.message ?: e.javaClass.simpleName
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
        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen()
            return
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer()
                if (summary.total > 0) {
                    Text(
                        stringResource(R.string.summary, summary.total, summary.ok),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showSettings = true }) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
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
                    Text(stringResource(if (showLog) R.string.log_hide else R.string.log_show))
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
                        Headline("📋", stringResource(R.string.idle_title))
                        Text(stringResource(R.string.idle_body))
                    }
                    is Phase.Working -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = content, strokeWidth = 3.dp)
                            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                            Text(stringResource(R.string.working, stringResource(p.step)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    Phase.NeedsVerification -> {
                        Headline("👇", stringResource(R.string.verify_title))
                        Text(stringResource(R.string.verify_body))
                    }
                    is Phase.Downloading -> {
                        Headline("⬇", stringResource(R.string.downloading, p.label))
                        if (p.total > 0) {
                            LinearProgressIndicator(
                                progress = { p.done.toFloat() / p.total },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = content,
                            )
                            Text(stringResource(R.string.progress, (p.done * 100 / p.total).toInt(), mb(p.done), mb(p.total)))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = content)
                            Text(mb(p.done))
                        }
                    }
                    is Phase.Done -> {
                        Headline("✓", stringResource(R.string.saved, p.label))
                        val dir = if (p.mime.startsWith("audio")) "Music" else if (p.mime.startsWith("image")) "Pictures" else "Movies"
                        Text(stringResource(R.string.saved_location, dir, MediaStoreDownloader.FOLDER))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { open(p.uri, p.mime) }) { Text(stringResource(R.string.open)) }
                            OutlinedButton(onClick = { share(p.uri, p.mime) }) { Text(stringResource(R.string.share)) }
                        }
                    }
                    is Phase.Failed -> {
                        Headline("✗", stringResource(R.string.failed))
                        Text(p.reason)
                        if (input.isNotBlank()) {
                            Button(onClick = { start(input) }) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showSettings = false }) { Text("←", style = MaterialTheme.typography.titleLarge) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            Row(
                Modifier.fillMaxWidth().clickable { autoStart = !autoStart; settings.autoStart = autoStart }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_auto_start), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_auto_start_desc), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoStart, onCheckedChange = { autoStart = it; settings.autoStart = it })
            }
            HorizontalDivider()

            Text(stringResource(R.string.settings_default_items), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.settings_default_items_desc), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(Item.NO_WATERMARK, Item.WATERMARK, Item.MUSIC).forEach { item ->
                val checked = item in defaultItems
                fun toggle() {
                    val next = if (checked) defaultItems - item else defaultItems + item
                    // 至少留一項：全部取消的話解析完什麼都不做，沒有意義。
                    if (next.isEmpty()) return
                    defaultItems = next
                    settings.defaultItems = next
                }
                Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Text(stringResource(item.label).replaceFirstChar { it.uppercase() })
                }
            }
            HorizontalDivider()

            // Android 13 起系統有「App 語言」設定頁，直接跳過去，不自己另做一套。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Column(
                    Modifier.fillMaxWidth().clickable {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
                        }
                    }.padding(vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_language_desc), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }

            Column(
                Modifier.fillMaxWidth().clickable { attempts.reset(); summary = attempts.summary() }.padding(vertical = 12.dp),
            ) {
                Text(stringResource(R.string.settings_reset_stats), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.summary, summary.total, summary.ok), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()

            Text(
                "v" + packageManager.getPackageInfo(packageName, 0).versionName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
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
                Text(a.title.ifBlank { stringResource(R.string.no_title) }, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                            Text((if (done) "✓ " else "") + stringResource(item.short))
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
                modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.manual_hint)) }, singleLine = true,
            )
            OutlinedButton(onClick = { start(input) }, enabled = !busy && capture != null && input.isNotBlank()) {
                Text(stringResource(R.string.download))
            }
        }
    }
}
