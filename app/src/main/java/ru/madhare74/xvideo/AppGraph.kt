package ru.madhare74.xvideo

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import ru.madhare74.xvideo.data.GuestApiSource
import ru.madhare74.xvideo.data.Http
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.SizeProbe
import ru.madhare74.xvideo.data.SyndicationSource
import ru.madhare74.xvideo.data.TcoResolver
import ru.madhare74.xvideo.download.DownloadScheduler
import ru.madhare74.xvideo.download.MediaSaver
import ru.madhare74.xvideo.download.Notifications
import ru.madhare74.xvideo.download.VideoFetcher
import ru.madhare74.xvideo.download.WorkDownloadScheduler
import ru.madhare74.xvideo.history.HistoryStore
import java.io.File

/** Общие объекты приложения. В UI-тестах источник данных подменяется через [repositoryOverride]. */
class AppGraph private constructor(context: Context) {
    val http = Http.client()
    private val defaultRepository = PostRepository(
        primary = SyndicationSource(http),
        fallback = GuestApiSource(http),
        shortLinks = TcoResolver(http),
    )
    val repository: PostRepository get() = repositoryOverride ?: defaultRepository
    val sizeProbe = SizeProbe(http)
    val fetcher = VideoFetcher(http)
    val saver = MediaSaver(context)
    val history = HistoryStore(File(context.filesDir, "history.json"))
    val scheduler: DownloadScheduler by lazy { WorkDownloadScheduler(context) }

    companion object {
        @Volatile private var instance: AppGraph? = null

        @Volatile var repositoryOverride: PostRepository? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}

class XVideoApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    /** Превью в истории берутся кадром из самого видеофайла — работает без сети. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
