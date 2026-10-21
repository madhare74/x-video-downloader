package ru.madhare74.xvideo

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.download.DownloadException
import ru.madhare74.xvideo.download.VideoFetcher
import ru.madhare74.xvideo.history.HistoryItem
import ru.madhare74.xvideo.history.HistoryStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class HistoryAndDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(id: String, at: Long) = HistoryItem(
        id = id, uri = "content://media/external/video/media/$id", fileName = "X_nasa_1_1_720p.mp4",
        authorName = "NASA", screenName = "nasa", postId = "1", width = 1280, height = 720, isGif = false, savedAt = at,
    )

    @Test
    fun historyPersistsNewestFirst() {
        val file = File(tmp.root, "history.json")
        val store = HistoryStore(file)
        store.add(item("1", 100))
        store.add(item("2", 200))
        assertEquals(listOf("2", "1"), store.items.value.map { it.id })

        val reloaded = HistoryStore(file)
        assertEquals(store.items.value, reloaded.items.value)

        reloaded.remove("2")
        assertEquals(listOf("1"), HistoryStore(file).items.value.map { it.id })
    }

    @Test
    fun brokenHistoryFileIsIgnored() {
        val file = File(tmp.root, "history.json").apply { writeText("{oops") }
        assertTrue(HistoryStore(file).items.value.isEmpty())
    }

    @Test
    fun downloadsWithProgress() = runTest {
        val server = MockWebServer().apply { start() }
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val out = ByteArrayOutputStream()
        var total: Long? = -1L
        var lastDone = 0L
        val done = VideoFetcher(OkHttpClient()).download(
            url = server.url("/v.mp4").toString(),
            output = { out },
            onStart = { total = it },
            onProgress = { d, _ -> lastDone = d },
        )
        assertEquals(300_000L, done)
        assertEquals(300_000L, total)
        assertEquals(300_000L, lastDone)
        assertArrayEquals(bytes, out.toByteArray())
        server.shutdown()
    }

    @Test
    fun httpErrorIsDownloadFailed() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(403))
        val e = runCatching {
            VideoFetcher(OkHttpClient()).download(server.url("/v.mp4").toString(), output = { ByteArrayOutputStream() })
        }.exceptionOrNull()
        assertEquals(AppError.DownloadFailed, (e as DownloadException).error)
        server.shutdown()
    }

    @Test
    fun noSpaceDetected() {
        assertEquals(AppError.NoSpace, VideoFetcher.classify(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(AppError.NoSpace, VideoFetcher.classify(IOException("x", IOException("No space left on device"))))
        assertEquals(AppError.DownloadFailed, VideoFetcher.classify(IOException("Connection reset")))
    }

    @Test
    fun writeErrorClassified() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10_000))))
        val full = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
        }
        val e = runCatching {
            VideoFetcher(OkHttpClient()).download(server.url("/v.mp4").toString(), output = { full })
        }.exceptionOrNull()
        assertEquals(AppError.NoSpace, (e as DownloadException).error)
        server.shutdown()
    }
}
