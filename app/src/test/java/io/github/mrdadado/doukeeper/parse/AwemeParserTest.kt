package io.github.mrdadado.doukeeper.parse

import io.github.mrdadado.doukeeper.share.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夾具是 2026-10-13 實測回應的**欄位結構**，網址縮短過（真的網址 3 小時就失效，留著沒有意義）。
 * 拿到真實圖文作品的回應後，要另外補一份圖文夾具——現在的 images 結構是推測的。
 */
class AwemeParserTest {

    private val video = """
        {"status_code":0,"aweme_detail":{
          "aweme_id":"7600000000000000123","desc":"測試影片 #話題",
          "author":{"nickname":"作者"},
          "video":{"duration":9134,
            "play_addr":{"url_list":["https://v5.zjcdn.com/nwm1","https://www.douyin.com/aweme/v1/play/?video_id=x"]},
            "download_addr":{"url_list":["https://v5.zjcdn.com/wm1","https://www.douyin.com/aweme/v1/play/?watermark=1"]}},
          "music":{"title":"原声","play_url":{"url_list":["https://lf9-music.douyinstatic.com/m.mp3"]}},
          "images":null}}
    """.trimIndent()

    @Test
    fun `一般影片`() {
        val a = (AwemeParser.parse(video, "7600000000000000123") as AwemeParser.Result.Ok).aweme
        assertEquals("作者", a.author)
        assertEquals(9134L, a.durationMs)
        assertEquals("https://v5.zjcdn.com/nwm1", a.videoNoWatermark.first())
        assertEquals(2, a.videoWatermark.size)
        assertEquals(listOf("https://lf9-music.douyinstatic.com/m.mp3"), a.musicUrls)
        assertFalse(a.isImagePost)
    }

    @Test
    fun `頁面送來別支作品時要拒收`() {
        // 實測 6 次中 1 次頁面被導到推薦影片。不擋的話會下載到別人的作品。
        val r = AwemeParser.parse(video, "7600000000000000456")
        assertEquals(AwemeParser.Result.WrongItem("7600000000000000123"), r)
    }

    @Test
    fun `沒有 aweme_detail`() {
        assertTrue(AwemeParser.parse("""{"status_code":8}""", "1") is AwemeParser.Result.Invalid)
        assertTrue(AwemeParser.parse("<html>", "1") is AwemeParser.Result.Invalid)
    }

    @Test
    fun `圖文作品`() {
        val json = """{"aweme_detail":{"aweme_id":"1","desc":"","author":{"nickname":"a"},
            "images":[{"url_list":["https://p/1a","https://p/1b"]},{"url_list":["https://p/2a"]},{"url_list":[]}]}}"""
        val a = (AwemeParser.parse(json, "1") as AwemeParser.Result.Ok).aweme
        assertTrue(a.isImagePost)
        assertEquals(listOf(listOf("https://p/1a", "https://p/1b"), listOf("https://p/2a")), a.images)
    }

    @Test
    fun `檔名去掉不合法字元並保留完整作品 ID`() {
        val a = (AwemeParser.parse(video, "7600000000000000123") as AwemeParser.Result.Ok).aweme
        assertEquals("作者_7600000000000000123.mp4", FileNames.video(a, watermark = false))
        assertEquals("作者_7600000000000000123_wm.mp4", FileNames.video(a, watermark = true))
        assertEquals("a_b_c", FileNames.sanitize("a/b:c"))
        assertEquals("douyin", FileNames.sanitize("..."))
        assertEquals(40, FileNames.sanitize("長".repeat(100)).length)
    }
}
