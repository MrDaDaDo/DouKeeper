package io.github.mrdadado.doukeeper.parse

import io.github.mrdadado.doukeeper.share.ShareTextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 短網址 `v.douyin.com/xxx/` → 作品 ID。
 *
 * 這一步純 HTTP 仍然可行（§5.B 實測：302 到 `iesdouyin.com/share/video/{id}/`）。
 * 只看導向後的網址，不讀頁面內容——那個頁面在部分地區本來就是錯誤頁。
 */
class ShortLinkResolver(private val http: OkHttpClient) {

    suspend fun resolveAwemeId(url: String): String? {
        ShareTextParser.extractAwemeId(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", MOBILE_UA).build()
            http.newCall(request).execute().use { response ->
                ShareTextParser.extractAwemeId(response.request.url.toString())
            }
        }
    }

    companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
    }
}
