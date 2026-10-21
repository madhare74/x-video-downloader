package ru.madhare74.xvideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.Messages
import ru.madhare74.xvideo.ui.MainViewModel
import ru.madhare74.xvideo.ui.Tags
import ru.madhare74.xvideo.ui.XApp
import ru.madhare74.xvideo.ui.XTheme

@RunWith(AndroidJUnit4::class)
class MainUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sources = TestData.byId(
        NASA_ID to "synd_single_video.json",
        "1849500000000000001" to "synd_multi_video.json",
        "1848800000000000777" to "synd_gif.json",
        "1848400000000000123" to "synd_no_video.json",
    )

    private fun launch(repository: PostRepository, clipboard: String? = null, scheduler: FakeScheduler = FakeScheduler()): MainViewModel {
        val vm = viewModel(repository, scheduler)
        composeRule.setContent { XTheme { XApp(vm, readClipboard = { clipboard }) } }
        composeRule.waitForIdle()
        return vm
    }

    private fun search(link: String) {
        composeRule.onNodeWithTag(Tags.INPUT).performTextInput(link)
        composeRule.onNodeWithTag(Tags.SEARCH).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun pasteLinkShowsQualities() {
        val scheduler = FakeScheduler()
        launch(TestData.repository(sources), clipboard = "Смотри https://twitter.com/NASA/status/1849012345678901234?s=20", scheduler = scheduler)
        composeRule.onNodeWithTag(Tags.PASTE).performClick()
        composeRule.onNodeWithTag(Tags.INPUT).assertTextContains(NASA_LINK)
        composeRule.onNodeWithTag(Tags.SEARCH).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(Tags.AUTHOR).assertIsDisplayed()
        composeRule.onNodeWithText("@NASA").assertIsDisplayed()
        val list = composeRule.onNodeWithTag(Tags.RESULT_LIST)
        val best = "${NASA_ID}_1_1920x1080"
        list.performScrollToNode(hasTestTag(Tags.quality(best)))
        composeRule.onNodeWithText("1920×1080 · 1080p").assertIsDisplayed()
        composeRule.onNodeWithText("Лучшее качество").assertExists()
        composeRule.onNodeWithText("0:45").assertExists()
        list.performScrollToNode(hasTestTag(Tags.quality("${NASA_ID}_1_480x270")))
        composeRule.onNodeWithText("480×270 · 270p").assertIsDisplayed()

        list.performScrollToNode(hasTestTag(Tags.download(best)))
        composeRule.onNodeWithTag(Tags.download(best)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, scheduler.enqueued.size)
        assertEquals("X_NASA_${NASA_ID}_1_1080p.mp4", scheduler.enqueued[0].baseFileName)
        composeRule.onNodeWithTag(Tags.downloadState(best)).assertExists()
    }

    @Test
    fun multipleVideosAndGif() {
        launch(TestData.repository(sources))
        search("https://x.com/test_channel/status/1849500000000000001")
        val list = composeRule.onNodeWithTag(Tags.RESULT_LIST)
        list.performScrollToNode(hasTestTag(Tags.video(2)))
        composeRule.onNodeWithText("Видео 2 из 2").assertExists()

        list.performScrollToNode(hasTestTag(Tags.INPUT))
        composeRule.onNodeWithTag(Tags.INPUT).performTextReplacement("https://x.com/devmemes/status/1848800000000000777")
        composeRule.onNodeWithTag(Tags.SEARCH).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("GIF").onFirst().assertExists()
        composeRule.onNodeWithText("480×270 · 270p").assertExists()
    }

    @Test
    fun invalidLinkMessage() {
        launch(TestData.repository(TestData.offline))
        search("https://example.com/hello")
        composeRule.onNodeWithText(Messages.text(AppError.InvalidLink)).assertIsDisplayed()
    }

    @Test
    fun noVideoMessage() {
        launch(TestData.repository(sources))
        search("https://x.com/photo_guy/status/1848400000000000123")
        composeRule.onNodeWithText("В этом посте нет видео.").assertIsDisplayed()
    }

    @Test
    fun offlineMessage() {
        launch(TestData.repository(TestData.offline, TestData.offline))
        search(NASA_LINK)
        composeRule.onNodeWithText(Messages.text(AppError.Unavailable)).assertIsDisplayed()
        composeRule.onNodeWithTag(Tags.RETRY_SEARCH).assertIsDisplayed()
    }

    @Test
    fun restrictedMessage() {
        launch(TestData.repository(TestData.syndication("synd_tombstone_protected.json")))
        search(NASA_LINK)
        composeRule.onNodeWithText(Messages.text(AppError.Restricted)).assertIsDisplayed()
    }

    @Test
    fun clipboardSuggestion() {
        val vm = launch(TestData.repository(sources))
        composeRule.runOnIdle { vm.onClipboard("https://x.com/NASA/status/1849012345678901234?s=46") }
        composeRule.onNodeWithTag(Tags.CLIPBOARD).assertIsDisplayed()
        assertEquals("ссылка не подставляется молча", "", vm.state.value.input)
        composeRule.onNodeWithTag(Tags.CLIPBOARD_ACCEPT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(Tags.AUTHOR).assertIsDisplayed()
    }
}
