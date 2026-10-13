package io.github.mrdadado.doukeeper.download

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.mrdadado.doukeeper.parse.ShortLinkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 把網址下載進 MediaStore 公用目錄（docs/SPEC.md §5.C、§5.D）。
 *
 * 先建立 IS_PENDING=1 的項目、寫完才設成 0：中途失敗時刪掉該項目，相簿裡不會留下殘缺檔。
 * 下載只需要 User-Agent，不需要 cookie 或 Referer（§5.B 實測）。
 */
class MediaStoreDownloader(
    private val http: OkHttpClient,
    private val resolver: ContentResolver,
) {

    enum class Kind(val collection: Uri, val directory: String, val mime: String) {
        VIDEO(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, "video/mp4"),
        IMAGE(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, "image/jpeg"),
        AUDIO(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, "audio/mpeg"),
    }

    /**
     * 下載失敗的原因。這一層沒有 Context，不能直接產生翻譯好的文字，
     * 所以只給原因代碼與數字，由畫面層對應到 strings.xml。
     */
    class DownloadException(val reason: Reason, val code: Int = 0, val done: Long = 0, val total: Long = 0) :
        IOException(reason.name)

    enum class Reason { NO_URLS, HTTP, EMPTY_BODY, MEDIA_STORE, INCOMPLETE }

    /** 依序嘗試每一個備用網址（同一個檔案的不同 CDN），全部失敗才丟出最後一個錯誤。 */
    suspend fun download(
        urls: List<String>,
        kind: Kind,
        displayName: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Uri = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) throw DownloadException(Reason.NO_URLS)
        var last: Exception? = null
        for (url in urls) {
            try {
                return@withContext downloadOne(url, kind, displayName, onProgress)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last!!
    }

    private fun downloadOne(
        url: String, kind: Kind, displayName: String,
        onProgress: (Long, Long) -> Unit,
    ): Uri {
        val request = Request.Builder().url(url).header("User-Agent", ShortLinkResolver.MOBILE_UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DownloadException(Reason.HTTP, code = response.code)
            val body = response.body ?: throw DownloadException(Reason.EMPTY_BODY)
            val total = body.contentLength()

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${kind.directory}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(kind.collection, values) ?: throw DownloadException(Reason.MEDIA_STORE)
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                        if (total > 0 && done != total) throw DownloadException(Reason.INCOMPLETE, done = done, total = total)
                    }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    companion object {
        /**
         * 存放的資料夾名稱。英文、而且**不跟著語言變**（使用者決定，2026-10-13）：
         * 跟著語言變的話，切換語言後檔案會散在兩個資料夾裡。
         * 跟 App 名稱一致。歷史：0.3.0 以前「抖音下載」、0.4–0.6 是「DouyinVideoDownloader」，
         * 舊檔案都留在原處，不自動搬。
         */
        const val FOLDER = "DouKeeper"
    }
}
