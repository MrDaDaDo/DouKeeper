package io.github.mrdadado.doukeeper

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每一次解析的成敗紀錄，用來回答「到底穩不穩定」（docs/SPEC.md §5.B、§7）。
 *
 * 實測證明失敗是時好時壞的，而且我們自己密集測試就會觸發風控、污染結果。
 * 所以穩定性只能從使用者平常的實際使用累積出來，不能靠一次性的測試判斷。
 *
 * 一行一筆、Tab 分隔，`adb shell run-as … cat files/attempts.tsv` 就能拉出來分析。
 */
class AttemptLog(dir: File) {

    private val file = File(dir, "attempts.tsv")
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    data class Summary(val ok: Int, val total: Int)

    fun record(awemeId: String, ok: Boolean, detail: String, ms: Long) {
        val clean = detail.replace('\t', ' ').replace('\n', ' ')
        file.appendText("${time.format(Date())}\t$awemeId\t${if (ok) "OK" else "FAIL"}\t${ms}ms\t$clean\n")
    }

    fun reset() {
        file.delete()
    }

    /** 最近 [last] 次裡成功幾次。 */
    fun summary(last: Int = 50): Summary {
        if (!file.exists()) return Summary(0, 0)
        val rows = file.readLines().filter { it.isNotBlank() }.takeLast(last)
        return Summary(rows.count { it.split('\t').getOrNull(2) == "OK" }, rows.size)
    }
}
