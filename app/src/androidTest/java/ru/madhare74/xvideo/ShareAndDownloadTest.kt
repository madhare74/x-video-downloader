package ru.madhare74.xvideo

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.madhare74.xvideo.data.FetchOutcome
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.PostSource
import ru.madhare74.xvideo.data.ShortLinkResolver
import ru.madhare74.xvideo.domain.Post
import ru.madhare74.xvideo.domain.PostVideo
import ru.madhare74.xvideo.domain.VideoVariant
import ru.madhare74.xvideo.download.DownloadRequest
import ru.madhare74.xvideo.history.AndroidMediaFiles
import ru.madhare74.xvideo.ui.MainViewModel
import ru.madhare74.xvideo.ui.Tags
import ru.madhare74.xvideo.ui.XApp
import ru.madhare74.xvideo.ui.XTheme

/** Открытие приложения через «Поделиться». */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        AppGraph.repositoryOverride = null
    }

    @Test
    fun shareFromXOpensAndSearches() {
        AppGraph.repositoryOverride = TestData.repository(TestData.byId(NASA_ID to "synd_single_video.json"))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://x.com/NASA/status/1849012345678901234?s=46&t=abc")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Так же, как система открывает приложение из меню «Поделиться».
        val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        try {
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag(Tags.AUTHOR).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("@NASA").assertIsDisplayed()
            composeRule.onNodeWithTag(Tags.RESULT_LIST).performScrollToNode(hasTestTag(Tags.quality("${NASA_ID}_1_1920x1080")))
            composeRule.onNodeWithText("1920×1080 · 1080p").assertIsDisplayed()
        } finally {
            activity.finish()
        }
    }
}

/** Скачивание тестового видео с подставного сервера: файл сохраняется и появляется в истории. */
@RunWith(AndroidJUnit4::class)
class DownloadTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val postId = "1849000000000000042"
    private val fileName = "X_tester_${postId}_1_720p.mp4"

    @After
    fun tearDown() {
        server.shutdown()
        cleanup()
    }

    private fun cleanup() {
        val graph = AppGraph.get(ctx)
        graph.history.items.value.filter { it.postId == postId }.forEach {
            runCatching { ctx.contentResolver.delete(Uri.parse(it.uri), null, null) }
            graph.history.remove(it.id)
        }
        runCatching {
            ctx.contentResolver.delete(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("X_tester_${postId}%"),
            )
        }
    }

    @Test
    fun downloadSavesFileAndAddsToHistory() {
        cleanup()
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes)))
        server.start()
        val variant = VideoVariant(server.url("/ext_tw_video/1/pu/vid/avc1/1280x720/test.mp4").toString(), 1280, 720, 2_176_000)
        val post = Post(postId, "Тестер", "tester", "Тестовое видео", listOf(PostVideo(1, null, 3000, false, listOf(variant))))
        val repository = PostRepository(PostSource { FetchOutcome.Found(post) }, null, ShortLinkResolver { null })

        val graph = AppGraph.get(ctx)
        val vm = MainViewModel({ repository }, null, graph.scheduler, graph.history, AndroidMediaFiles(ctx, graph.saver))
        composeRule.setContent { XTheme { XApp(vm, readClipboard = { null }) } }

        composeRule.onNodeWithTag(Tags.INPUT).performTextInput("https://x.com/tester/status/$postId")
        composeRule.onNodeWithTag(Tags.SEARCH).performClick()
        val key = DownloadRequest.key(postId, 1, variant)
        composeRule.onNodeWithTag(Tags.RESULT_LIST).performScrollToNode(hasTestTag(Tags.download(key)))
        composeRule.onNodeWithTag(Tags.download(key)).performClick()

        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Сохранено").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("/ext_tw_video/1/pu/vid/avc1/1280x720/test.mp4", server.takeRequest().path)

        val item = graph.history.items.value.first { it.postId == postId }
        assertEquals(fileName, item.fileName)
        val size = ctx.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readBytes().size }
        assertEquals(bytes.size, size)

        composeRule.onNodeWithTag(Tags.TAB_HISTORY).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(Tags.historyItem(fileName)).assertIsDisplayed()
        composeRule.onNodeWithText("1280×720").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Файл удалён").fetchSemanticsNodes().isEmpty())
    }
}
