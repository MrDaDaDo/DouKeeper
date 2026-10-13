package io.github.mrdadado.doukeeper.download

import androidx.annotation.StringRes
import io.github.mrdadado.doukeeper.R

/** 一個作品可下載的項目。按鈕文字、設定頁勾選、完成打勾都靠這個。 */
enum class DownloadItem(@StringRes val label: Int, @StringRes val short: Int) {
    NO_WATERMARK(R.string.item_no_watermark, R.string.item_no_watermark_short),
    WATERMARK(R.string.item_watermark, R.string.item_watermark_short),
    MUSIC(R.string.item_music, R.string.item_music_short),
    IMAGES(R.string.item_images, R.string.item_images_short),
}
