package io.github.mrdadado.doukeeper

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 快速設定的磚塊：在抖音按「複製連結」→ 下拉通知欄 → 點這個磚塊。
 *
 * 抖音的分享面板是自己畫的，叫不出系統分享選單（docs/SPEC.md B2 實測），
 * 這是最接近「分享 → 選 App」的替代入口。
 *
 * 磚塊本身**不讀剪貼簿**：Android 10 起只有畫面在最前面的 App 能讀，
 * 背景的 TileService 讀到的永遠是空的。所以它只負責把 MainActivity 叫到前面，
 * 由 MainActivity 拿到焦點後讀剪貼簿。
 */
class DownloadTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 起只接受 PendingIntent 版本，傳 Intent 會直接丟例外。
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
