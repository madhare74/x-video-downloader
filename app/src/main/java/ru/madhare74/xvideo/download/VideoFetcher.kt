package ru.madhare74.xvideo.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.FileNames
import ru.madhare74.xvideo.domain.VideoVariant
import java.io.IOException
import java.io.OutputStream

/** Что скачать и под каким именем. [key] — уникален для поста, номера видео и качества. */
data class DownloadRequest(
    val key: String,
    val url: String,
    val postId: String,
    val screenName: String,
    val authorName: String,
    val videoIndex: Int,
    val width: Int,
    val height: Int,
    val isGif: Boolean,
) {
    val baseFileName: String get() = FileNames.build(screenName, postId, videoIndex, height)

    companion object {
        fun key(postId: String, videoIndex: Int, variant: VideoVariant) =
            "${postId}_${videoIndex}_${variant.width}x${variant.height}"
    }
}

/** Ошибка скачивания, понятная пользователю. */
class DownloadException(val error: AppError, cause: Throwable? = null) : IOException(error.name, cause)

/**
 * Потоковое скачивание файла по HTTP в [OutputStream] с отчётом о ходе.
 * [onStart] получает размер файла (если сервер его сообщил) до записи первого байта.
 */
class VideoFetcher(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        output: () -> OutputStream,
        onStart: (total: Long?) -> Unit = {},
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: IOException) {
            throw DownloadException(AppError.DownloadFailed, e)
        }
        response.use { r ->
            if (!r.isSuccessful) throw DownloadException(AppError.DownloadFailed, IOException("HTTP ${r.code}"))
            val body = r.body ?: throw DownloadException(AppError.DownloadFailed)
            val total = body.contentLength().takeIf { it > 0 }
            onStart(total)
            var done = 0L
            try {
                output().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastReport > 250_000_000L) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                throw if (e is DownloadException) e else DownloadException(classify(e), e)
            }
            if (total != null && done < total) throw DownloadException(AppError.DownloadFailed)
            onProgress(done, total)
            done
        }
    }

    companion object {
        /** Нехватка места видна по ENOSPC в тексте ошибки записи. */
        fun classify(e: Throwable): AppError {
            var t: Throwable? = e
            while (t != null) {
                val m = t.message.orEmpty()
                if ("ENOSPC" in m || "No space left" in m) return AppError.NoSpace
                t = t.cause
            }
            return AppError.DownloadFailed
        }
    }
}
