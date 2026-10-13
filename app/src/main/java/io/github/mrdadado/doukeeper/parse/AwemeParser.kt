package io.github.mrdadado.doukeeper.parse

import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析網頁版 `/aweme/v1/web/aweme/detail/` 的回應（docs/SPEC.md §5.B）。
 *
 * 只取需要的欄位、逐層用 opt 走訪，不建完整資料類別：回應有好幾百個欄位而且常變，
 * 綁死整個結構等於抖音動任何一個無關欄位都會壞。
 */
object AwemeParser {

    sealed interface Result {
        data class Ok(val aweme: Aweme) : Result
        /** 拿到的是別支作品——頁面有時會被導到推薦影片（§5.B 實測 6 次中 1 次）。 */
        data class WrongItem(val got: String) : Result
        /** reason 是給「詳細記錄」看的技術描述，不翻譯。 */
        data class Invalid(val reason: String) : Result
    }

    fun parse(json: String, expectedId: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Result.Invalid("not JSON")
        val detail = root.optJSONObject("aweme_detail")
            ?: return Result.Invalid("no aweme_detail (status_code=${root.opt("status_code")})")

        val id = detail.optString("aweme_id")
        if (id != expectedId) return Result.WrongItem(id)

        val video = detail.optJSONObject("video")
        val music = detail.optJSONObject("music")
        val images = detail.optJSONArray("images")
            ?.objects()
            ?.map { it.urlList("url_list") }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return Result.Ok(
            Aweme(
                id = id,
                title = detail.optString("desc"),
                author = detail.optJSONObject("author")?.optString("nickname").orEmpty(),
                durationMs = video?.optLong("duration") ?: 0L,
                videoNoWatermark = video?.optJSONObject("play_addr")?.urlList("url_list").orEmpty(),
                videoWatermark = video?.optJSONObject("download_addr")?.urlList("url_list").orEmpty(),
                musicUrls = music?.optJSONObject("play_url")?.urlList("url_list").orEmpty(),
                musicTitle = music?.optString("title")?.takeIf { it.isNotEmpty() },
                images = images,
            )
        )
    }

    private fun JSONObject.urlList(key: String): List<String> =
        optJSONArray(key)?.strings().orEmpty()

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
