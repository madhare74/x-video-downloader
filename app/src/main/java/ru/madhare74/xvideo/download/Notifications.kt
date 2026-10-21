package ru.madhare74.xvideo.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.madhare74.xvideo.MainActivity
import ru.madhare74.xvideo.R
import ru.madhare74.xvideo.domain.RuFormat

object Notifications {
    private const val CHANNEL_PROGRESS = "downloads"
    private const val CHANNEL_DONE = "downloads_done"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Скачивание", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Готовые видео", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun progressId(key: String) = key.hashCode()
    private fun resultId(key: String) = key.hashCode() xor 0x5A5A5A5A

    private fun appIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun progress(
        context: Context,
        title: String,
        done: Long,
        total: Long?,
        cancel: PendingIntent,
    ) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_download)
        .setContentTitle(title)
        .setContentText(if (done == 0L && total == null) "Подключение…" else RuFormat.progress(done, total))
        .setProgress(100, total?.let { (done * 100 / it).toInt() } ?: 0, total == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(appIntent(context))
        .addAction(0, "Отмена", cancel)
        .build()

    fun openIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun saved(context: Context, key: String, fileName: String, uri: Uri) {
        val open = PendingIntent.getActivity(
            context, resultId(key), openIntent(uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context, resultId(key),
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Видео сохранено")
                .setContentText(fileName)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Открыть", open)
                .build(),
        )
    }

    fun failed(context: Context, key: String, message: String) {
        post(
            context, resultId(key),
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Видео не скачано")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(appIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Уведомления запрещены пользователем.
        }
    }
}
