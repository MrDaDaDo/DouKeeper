package io.github.mrdadado.doukeeper.share

/**
 * 從抖音分享出來的整段文字裡找出網址（docs/SPEC.md §5.A）。
 *
 * 分享文字長這樣，網址前後都黏著中文：
 * `1.23 abc:/ 01/01 標題 # 話題  https://v.douyin.com/AbCdEfGh123/ 复制此链接，打开Dou音搜索，直接观看视频！`
 *
 * 網址的字元集只收 ASCII 的網址字元，所以會自然停在後面的中文前面——
 * 不能用「到空白為止」，因為有時候網址後面直接接中文，中間沒有空白。
 */
object ShareTextParser {

    private val douyinUrl = Regex(
        """https?://(?:[A-Za-z0-9-]+\.)*(?:douyin|iesdouyin)\.com(?:/[A-Za-z0-9\-._~%!$&'()*+,;=:@/?#]*)?"""
    )

    fun extractUrl(text: String): String? =
        douyinUrl.find(text)?.value?.trimEnd('.', ',', ')', '!', '?')

    private val awemeIdInUrl = listOf(
        Regex("""/(?:video|note|slides)/(\d{8,})"""),
        Regex("""[?&](?:modal_id|aweme_id|item_ids)=(\d{8,})"""),
    )

    /** 從（已經展開的）網址取出作品 ID。短網址要先跟隨導向才會有 ID。 */
    fun extractAwemeId(url: String): String? =
        awemeIdInUrl.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
}
