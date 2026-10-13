package io.github.mrdadado.doukeeper.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.mrdadado.doukeeper.MainActivity
import io.github.mrdadado.doukeeper.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 下載期間的前景服務：撐住 process 不被回收，並在通知欄顯示進度（docs/SPEC.md §5.C）。
 *
 * 它本身不下載，只看 [DownloadCenter] 的狀態更新通知；佇列清空就結束自己，
 * 並另外留一則「已儲存／失敗」的結果通知（點一下開啟檔案）。
 *
 * 結果通知用另一個 ID：前景通知在 stopForeground 時會被移除，同一個 ID 的話結果也跟著消失。
 *
 * 沒有通知權限（Android 13+ 被拒）時下載照樣進行，只是通知欄看不到。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // startForegroundService 之後 5 秒內一定要呼叫，否則系統直接殺掉 App。
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progress(getString(R.string.notif_preparing), 0, -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch {
            DownloadCenter.state.combine(DownloadCenter.busy) { s, busy -> s to busy }.collect { (s, busy) ->
                when (s) {
                    is DownloadCenter.State.Running -> manager.notify(PROGRESS_ID, progress(s.label, s.done, s.total))
                    else -> if (!busy) finish(s)
                }
            }
        }
    }

    /**
     * 已經在跑的時候又被 startForegroundService 叫一次（使用者排了第二支）：
     * 每一次都要再呼叫 startForeground，否則系統會當成「答應要前景卻沒做到」而殺掉 App。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = DownloadCenter.state.value
        val n = if (s is DownloadCenter.State.Running) progress(s.label, s.done, s.total)
            else progress(getString(R.string.notif_preparing), 0, -1)
        ServiceCompat.startForeground(this, PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(s: DownloadCenter.State) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        result(s)?.let { manager.notify(RESULT_ID, it) }
        stopSelf()
    }

    private fun progress(label: String, done: Long, total: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_download)
            .setContentTitle(getString(R.string.downloading, label))
            .setContentText(
                if (total > 0) getString(R.string.progress, (done * 100 / total).toInt(), DownloadCenter.mb(done), DownloadCenter.mb(total))
                else if (done > 0) DownloadCenter.mb(done) else null
            )
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun result(s: DownloadCenter.State): Notification? {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_download)
            .setAutoCancel(true)
        return when (s) {
            is DownloadCenter.State.Done -> b
                .setContentTitle(getString(R.string.saved, s.label))
                .setContentText(getString(R.string.notif_tap_to_open))
                .setContentIntent(openFile(s.uri, s.mime))
                .build()
            is DownloadCenter.State.Failed -> b
                .setContentTitle(getString(R.string.failed))
                .setContentText(s.reason)
                .setContentIntent(openApp())
                .build()
            else -> null
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openFile(uri: android.net.Uri, mime: String): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "downloads"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2
    }
}
