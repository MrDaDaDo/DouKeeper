package io.github.mrdadado.doukeeper.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTextParserTest {

    @Test
    fun `真實分享文字`() {
        // 2026-10-13 使用者從抖音實際分享出來的文字。
        val text = "1.23 abc:/ 01/01 測試影片標題 # 話題  " +
            "https://v.douyin.com/AbCdEfGh123/ 复制此链接，打开Dou音搜索，直接观看视频！"
        assertEquals("https://v.douyin.com/AbCdEfGh123/", ShareTextParser.extractUrl(text))
    }

    @Test
    fun `網址後面直接接中文沒有空白`() {
        assertEquals(
            "https://v.douyin.com/abc123/",
            ShareTextParser.extractUrl("看看https://v.douyin.com/abc123/复制此链接"),
        )
    }

    @Test
    fun `網址後面接中文句號`() {
        assertEquals("https://v.douyin.com/abc123/", ShareTextParser.extractUrl("https://v.douyin.com/abc123/。"))
    }

    @Test
    fun `只有網址`() {
        assertEquals("https://www.douyin.com/video/7600000000000000123",
            ShareTextParser.extractUrl("https://www.douyin.com/video/7600000000000000123"))
    }

    @Test
    fun `沒有抖音網址`() {
        assertNull(ShareTextParser.extractUrl("复制此链接 https://example.com/x"))
        assertNull(ShareTextParser.extractUrl(""))
    }

    @Test
    fun `不會把長得像的網域當成抖音`() {
        assertNull(ShareTextParser.extractUrl("https://notdouyin.com/video/123456789"))
    }

    @Test
    fun `從展開後的網址取作品 ID`() {
        // 短網址實際導向的目標（2026-10-13 實測）。
        assertEquals("7600000000000000123", ShareTextParser.extractAwemeId(
            "https://www.iesdouyin.com/share/video/7600000000000000123/?region=XX&mid=7600000000000000789"))
        assertEquals("7600000000000000123", ShareTextParser.extractAwemeId(
            "https://www.douyin.com/jingxuan?modal_id=7600000000000000123"))
        assertEquals("7600000000000000001", ShareTextParser.extractAwemeId(
            "https://www.iesdouyin.com/share/note/7600000000000000001/"))
        assertNull(ShareTextParser.extractAwemeId("https://v.douyin.com/AbCdEfGh123/"))
    }
}
