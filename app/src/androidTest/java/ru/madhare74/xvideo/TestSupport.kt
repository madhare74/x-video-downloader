package ru.madhare74.xvideo

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.madhare74.xvideo.data.FetchOutcome
import ru.madhare74.xvideo.data.GuestApiParser
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.PostSource
import ru.madhare74.xvideo.data.ShortLinkResolver
import ru.madhare74.xvideo.data.SyndicationParser
import ru.madhare74.xvideo.download.DownloadRequest
import ru.madhare74.xvideo.download.DownloadScheduler
import ru.madhare74.xvideo.download.DownloadState
import ru.madhare74.xvideo.history.DeleteResult
import ru.madhare74.xvideo.history.HistoryStore
import ru.madhare74.xvideo.history.MediaFiles
import ru.madhare74.xvideo.ui.MainViewModel
import java.io.File
import java.io.IOException

/** Сохранённые образцы ответов X (assets тестового APK). Автотесты к X не обращаются. */
object TestData {
    fun fixture(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    fun syndication(name: String) = PostSource { SyndicationParser.parse(fixture(name)) }

    val offline = PostSource { throw IOException("Нет сети") }

    fun repository(primary: PostSource, fallback: PostSource? = null) =
        PostRepository(primary, fallback, ShortLinkResolver { null })

    /** Источник по ID поста: каждому посту — свой образец ответа. */
    fun byId(vararg pairs: Pair<String, String>) = PostSource { id ->
        val name = pairs.toMap()[id] ?: return@PostSource FetchOutcome.NotFound
        if (name.startsWith("gql")) GuestApiParser.parse(fixture(name)) else SyndicationParser.parse(fixture(name))
    }

    fun tempHistory(): HistoryStore {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "history-test-${System.nanoTime()}.json")
        return HistoryStore(file)
    }
}

class FakeScheduler : DownloadScheduler {
    val flow = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val enqueued = mutableListOf<DownloadRequest>()
    override val states: Flow<Map<String, DownloadState>> = flow
    override fun enqueue(request: DownloadRequest) {
        enqueued += request
        flow.update { it + (request.key to DownloadState.Queued) }
    }
    override fun cancel(key: String) = flow.update { it + (key to DownloadState.Cancelled) }
}

class FakeFiles(private val missing: Set<String> = emptySet()) : MediaFiles {
    override fun exists(uri: String) = uri !in missing
    override fun delete(uri: String) = DeleteResult.Deleted
}

fun viewModel(
    repository: PostRepository,
    scheduler: DownloadScheduler = FakeScheduler(),
    history: HistoryStore = TestData.tempHistory(),
    files: MediaFiles = FakeFiles(),
) = MainViewModel({ repository }, null, scheduler, history, files)

const val NASA_LINK = "https://x.com/NASA/status/1849012345678901234"
const val NASA_ID = "1849012345678901234"
