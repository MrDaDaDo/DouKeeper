package io.github.mrdadado.doukeeper.share

import io.github.mrdadado.doukeeper.parse.Aweme

/**
 * 產生下載檔名：`{作者}_{作品ID}[_wm|_music|_01].{副檔名}`（docs/SPEC.md §5.C）。
 *
 * 作者暱稱什麼字元都可能有（斜線、emoji、換行），檔名不允許的一律換掉；
 * 長度限制在作者那一段，作品 ID 永遠完整保留——它才是能對回原作品的部分。
 */
object FileNames {

    private const val MAX_AUTHOR = 40
    private val illegal = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    fun video(a: Aweme, watermark: Boolean) = base(a) + (if (watermark) "_wm" else "") + ".mp4"
    fun music(a: Aweme) = base(a) + "_music.mp3"
    fun image(a: Aweme, index: Int) = base(a) + "_%02d.jpg".format(index + 1)

    fun sanitize(name: String): String =
        name.replace(illegal, "_").trim().trim('.').take(MAX_AUTHOR).ifEmpty { "douyin" }

    private fun base(a: Aweme) = sanitize(a.author) + "_" + a.id
}
