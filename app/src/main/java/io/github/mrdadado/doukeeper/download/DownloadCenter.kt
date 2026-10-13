package io.github.mrdadado.doukeeper.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import io.github.mrdadado.doukeeper.R
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.DownloadException
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.Kind
import io.github.mrdadado.doukeeper.download.MediaStoreDownloader.Reason
import io.github.mrdadado.doukeeper.parse.Aweme
import io.github.mrdadado.doukeeper.share.FileNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 下載的執行者，活得比畫面久（docs/SPEC.md §5.C）。
 *
 * 為什麼不放在 MainActivity：使用者要能下載中就切去別的 App（2026-10-13 使用者回報
 * 「要一直等他下載才能切換」）。下載綁在畫面上的話，畫面一被系統回收下載就斷了。
 * 這裡用 process 層級的 scope 跑，並由 [DownloadService] 這個前景服務撐住 process、
 * 在通知欄顯示進度。畫面只負責「解析」（需要 WebView，可能要人機驗證）。
 *
 * 多次 [enqueue] 會排隊依序執行，不會同時開好幾條下載搶頻寬。
 */
object DownloadCenter {

    sealed interface State {
        data object Idle : State
        data class Running(val label: String, val done: Long, val total: Long) : State
        data class Done(val label: String, val uri: Uri, val mime: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 目前這個作品已經下載好的項目，給作品卡打勾用。換作品時由 [reset] 清掉。 */
    private val _downloaded = MutableStateFlow<Map<DownloadItem, Uri>>(emptyMap())
    val downloaded: StateFlow<Map<DownloadItem, Uri>> = _downloaded

    /** 佇列裡還有工作。前景服務看這個決定什麼時候結束。 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tail: Job? = null
    private var pending = 0

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** 換了一個新作品：清掉上一個作品的打勾與狀態。下載中的工作不受影響。 */
    fun reset() {
        _downloaded.value = emptyMap()
        if (!_busy.value) _state.value = State.Idle
    }

    fun enqueue(context: Context, a: Aweme, items: List<DownloadItem>, onFinished: (ok: Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val downloader = MediaStoreDownloader(http, app.contentResolver)
        pending++
        _busy.value = true
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        val previous = tail
        tail = scope.launch {
            previous?.join()
            var ok = true
            try {
                for (item in items) ok = run(app, downloader, a, item) && ok
            } finally {
                onFinished(ok)
                if (--pending == 0) _busy.value = false
            }
        }
    }

    private suspend fun run(app: Context, downloader: MediaStoreDownloader, a: Aweme, item: DownloadItem): Boolean {
        val targets: List<Triple<List<String>, Kind, String>> = when (item) {
            DownloadItem.NO_WATERMARK -> listOf(Triple(a.videoNoWatermark, Kind.VIDEO, FileNames.video(a, watermark = false)))
            DownloadItem.WATERMARK -> listOf(Triple(a.videoWatermark, Kind.VIDEO, FileNames.video(a, watermark = true)))
            DownloadItem.MUSIC -> listOf(Triple(a.musicUrls, Kind.AUDIO, FileNames.music(a)))
            DownloadItem.IMAGES -> a.images.mapIndexed { i, urls -> Triple(urls, Kind.IMAGE, FileNames.image(a, i)) }
        }
        if (targets.isEmpty() || targets.any { it.first.isEmpty() }) {
            _state.value = State.Failed(app.getString(R.string.error_item_missing, app.getString(item.label)))
            return false
        }
        var last: Uri? = null
        for ((i, target) in targets.withIndex()) {
            val (urls, kind, name) = target
            val label = if (targets.size > 1) app.getString(R.string.part_of, app.getString(item.label), i + 1, targets.size)
                else app.getString(item.label)
            _state.value = State.Running(label, 0, -1)
            var lastPct = -1L
            val result = runCatching {
                downloader.download(urls, kind, name) { done, total ->
                    // 每 64KB 回報一次太頻繁；只在百分比（未知大小時每 1MB）變動時更新。
                    val pct = if (total > 0) done * 100 / total else done / (1024 * 1024)
                    if (pct != lastPct) {
                        lastPct = pct
                        _state.value = State.Running(label, done, total)
                    }
                }
            }
            last = result.getOrElse {
                _state.value = State.Failed(app.getString(R.string.error_download, describe(app, it)))
                return false
            }
        }
        _downloaded.value = _downloaded.value + (item to last!!)
        _state.value = State.Done(app.getString(item.label), last, targets.last().second.mime)
        return true
    }

    fun describe(app: Context, e: Throwable): String = when (e) {
        is DownloadException -> when (e.reason) {
            Reason.NO_URLS -> app.getString(R.string.error_no_urls)
            Reason.HTTP -> app.getString(R.string.error_http, e.code)
            Reason.EMPTY_BODY -> app.getString(R.string.error_empty_body)
            Reason.MEDIA_STORE -> app.getString(R.string.error_media_store)
            Reason.INCOMPLETE -> app.getString(R.string.error_incomplete, mb(e.done), mb(e.total))
        }
        // 網路層的例外（逾時、DNS…）訊息本來就是英文的技術描述，原樣顯示。
        else -> e.message ?: e.javaClass.simpleName
    }

    fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
