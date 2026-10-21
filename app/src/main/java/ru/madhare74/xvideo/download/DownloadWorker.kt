package ru.madhare74.xvideo.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.madhare74.xvideo.AppGraph
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.Messages
import ru.madhare74.xvideo.history.HistoryItem

/** Состояние скачивания одного варианта видео — для карточки на главном экране. */
sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val done: Long, val total: Long?) : DownloadState
    data class Done(val uri: String) : DownloadState
    data class Failed(val error: AppError) : DownloadState
    data object Cancelled : DownloadState
}

interface DownloadScheduler {
    val states: Flow<Map<String, DownloadState>>
    fun enqueue(request: DownloadRequest)
    fun cancel(key: String)
}

/** Фоновое скачивание через WorkManager: переживает сворачивание приложения, несколько загрузок идут параллельно. */
class WorkDownloadScheduler(context: Context) : DownloadScheduler {

    private val wm = WorkManager.getInstance(context.applicationContext)

    override val states: Flow<Map<String, DownloadState>> =
        wm.getWorkInfosByTagFlow(TAG).map { infos ->
            infos
                .groupBy { info -> info.tags.firstOrNull { it.startsWith(KEY_TAG) }?.removePrefix(KEY_TAG) }
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { (_, list) -> toState(list.maxBy(::startedAt)) }
        }

    override fun enqueue(request: DownloadRequest) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(DownloadData.toData(request))
            .addTag(TAG)
            .addTag(KEY_TAG + request.key)
            .addTag(TIME_TAG + System.currentTimeMillis())
            .build()
        wm.enqueueUniqueWork(UNIQUE_PREFIX + request.key, ExistingWorkPolicy.REPLACE, work)
    }

    override fun cancel(key: String) {
        wm.cancelUniqueWork(UNIQUE_PREFIX + key)
    }

    private fun startedAt(info: WorkInfo): Long =
        info.tags.firstOrNull { it.startsWith(TIME_TAG) }?.removePrefix(TIME_TAG)?.toLongOrNull() ?: 0L

    private fun toState(info: WorkInfo): DownloadState = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
        WorkInfo.State.RUNNING -> {
            val done = info.progress.getLong(DownloadData.DONE, 0L)
            val total = info.progress.getLong(DownloadData.TOTAL, -1L).takeIf { it > 0 }
            DownloadState.Running(done, total)
        }
        WorkInfo.State.SUCCEEDED -> DownloadState.Done(info.outputData.getString(DownloadData.URI).orEmpty())
        WorkInfo.State.FAILED -> DownloadState.Failed(DownloadData.error(info.outputData))
        WorkInfo.State.CANCELLED -> DownloadState.Cancelled
    }

    companion object {
        const val TAG = "download"
        private const val KEY_TAG = "key:"
        private const val TIME_TAG = "t:"
        private const val UNIQUE_PREFIX = "dl:"
    }
}

internal object DownloadData {
    const val DONE = "done"
    const val TOTAL = "total"
    const val URI = "uri"
    const val FILE = "file"
    const val ERROR = "error"

    fun toData(r: DownloadRequest): Data = workDataOf(
        "key" to r.key,
        "url" to r.url,
        "postId" to r.postId,
        "screenName" to r.screenName,
        "authorName" to r.authorName,
        "videoIndex" to r.videoIndex,
        "width" to r.width,
        "height" to r.height,
        "isGif" to r.isGif,
    )

    fun fromData(d: Data): DownloadRequest? {
        val key = d.getString("key") ?: return null
        val url = d.getString("url") ?: return null
        return DownloadRequest(
            key = key,
            url = url,
            postId = d.getString("postId").orEmpty(),
            screenName = d.getString("screenName").orEmpty(),
            authorName = d.getString("authorName").orEmpty(),
            videoIndex = d.getInt("videoIndex", 1),
            width = d.getInt("width", 0),
            height = d.getInt("height", 0),
            isGif = d.getBoolean("isGif", false),
        )
    }

    fun error(d: Data): AppError =
        d.getString(ERROR)?.let { name -> AppError.entries.firstOrNull { it.name == name } } ?: AppError.DownloadFailed
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val request = DownloadData.fromData(inputData) ?: return failure(AppError.DownloadFailed)
        val ctx = applicationContext
        val graph = AppGraph.get(ctx)
        Notifications.ensureChannels(ctx)
        val title = buildString {
            append(if (request.isGif) "GIF" else "Видео")
            append(" ${request.videoIndex} · @${request.screenName}")
            if (request.height > 0) append(" · ${request.height}p")
        }
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val notificationId = Notifications.progressId(request.key)

        try {
            setForeground(foregroundInfo(notificationId, Notifications.progress(ctx, title, 0, null, cancel)))
        } catch (e: Exception) {
            // Если показать постоянное уведомление нельзя, скачиваем без него.
        }

        val target = try {
            graph.saver.create(request.baseFileName)
        } catch (e: Exception) {
            return failure(AppError.DownloadFailed, request)
        }

        return try {
            graph.fetcher.download(
                url = request.url,
                output = { target.open() },
                onStart = { total ->
                    if (total != null && total + RESERVE_BYTES > graph.saver.freeBytes()) {
                        throw DownloadException(AppError.NoSpace)
                    }
                },
                onProgress = { done, total ->
                    setProgressAsync(workDataOf(DownloadData.DONE to done, DownloadData.TOTAL to (total ?: -1L)))
                    notify(notificationId, Notifications.progress(ctx, title, done, total, cancel))
                },
            )
            val uri = target.commit()
            graph.history.add(
                HistoryItem(
                    id = uri.toString(),
                    uri = uri.toString(),
                    fileName = target.fileName,
                    authorName = request.authorName,
                    screenName = request.screenName,
                    postId = request.postId,
                    width = request.width,
                    height = request.height,
                    isGif = request.isGif,
                    savedAt = System.currentTimeMillis(),
                ),
            )
            Notifications.saved(ctx, request.key, target.fileName, uri)
            Result.success(workDataOf(DownloadData.URI to uri.toString(), DownloadData.FILE to target.fileName))
        } catch (e: CancellationException) {
            target.discard()
            throw e
        } catch (e: DownloadException) {
            target.discard()
            failure(e.error, request)
        } catch (e: Exception) {
            target.discard()
            failure(VideoFetcher.classify(e), request)
        } finally {
            NotificationManagerCompat.from(ctx).cancel(notificationId)
        }
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
        } catch (e: SecurityException) {
            // Нет разрешения на уведомления — прогресс виден только в приложении.
        }
    }

    private fun failure(error: AppError, request: DownloadRequest? = null): Result {
        if (request != null && !isStopped) Notifications.failed(applicationContext, request.key, Messages.text(error))
        return Result.failure(workDataOf(DownloadData.ERROR to error.name))
    }

    private fun foregroundInfo(id: Int, notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }

    companion object {
        /** Запас свободного места сверх размера файла. */
        private const val RESERVE_BYTES = 20L * 1024 * 1024
    }
}
