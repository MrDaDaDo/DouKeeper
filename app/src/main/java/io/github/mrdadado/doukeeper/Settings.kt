package io.github.mrdadado.doukeeper

import android.content.Context

/**
 * 使用者設定（docs/SPEC.md §6）。項目很少，用 SharedPreferences 就夠，不引入 DataStore。
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 打開 App 時，剪貼簿裡有新的抖音連結就直接開始。關掉的話只填進輸入框。 */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /**
     * 解析完自動下載哪些項目。圖文作品沒有影片，一律下載圖片，再加上這裡有勾的配樂。
     * 一項都沒勾的時候退回無浮水印影片——「解析完什麼都不做」不是有用的狀態。
     */
    var defaultItems: Set<MainActivity.Item>
        get() = prefs.getStringSet(KEY_DEFAULT_ITEMS, null)
            ?.mapNotNull { name -> MainActivity.Item.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(MainActivity.Item.NO_WATERMARK)
        set(value) = prefs.edit().putStringSet(KEY_DEFAULT_ITEMS, value.map { it.name }.toSet()).apply()

    companion object {
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_DEFAULT_ITEMS = "default_items"
    }
}
