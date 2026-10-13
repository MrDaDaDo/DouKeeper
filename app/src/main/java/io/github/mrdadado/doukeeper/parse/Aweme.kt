package io.github.mrdadado.doukeeper.parse

/**
 * 一個抖音作品裡我們用得到的部分（docs/SPEC.md §4）。
 *
 * 每個網址欄位都是**清單**：抖音給的是多個 CDN 的同一個檔案，第一個失敗就換下一個。
 * 網址約 3 小時後失效，所以這個物件只在解析完的當下有用，不要存起來。
 */
data class Aweme(
    val id: String,
    val title: String,
    val author: String,
    val durationMs: Long,
    val videoNoWatermark: List<String>,
    val videoWatermark: List<String>,
    val musicUrls: List<String>,
    val musicTitle: String?,
    /** 圖文作品的每一張圖；每張圖本身也是一組備用網址。一般影片是空的。 */
    val images: List<List<String>>,
) {
    val isImagePost: Boolean get() = images.isNotEmpty()
}
