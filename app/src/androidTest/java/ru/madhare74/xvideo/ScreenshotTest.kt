package ru.madhare74.xvideo

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.ShortLinkResolver
import ru.madhare74.xvideo.download.DownloadState
import ru.madhare74.xvideo.history.HistoryItem
import ru.madhare74.xvideo.ui.MainViewModel
import ru.madhare74.xvideo.ui.Tab
import ru.madhare74.xvideo.ui.Tags
import ru.madhare74.xvideo.ui.XApp
import ru.madhare74.xvideo.ui.XTheme
import java.io.File

/**
 * Снимки экранов в светлой и тёмной теме и при крупном системном шрифте (100%, 150%, 200%)
 * для визуальной проверки вёрстки. Складываются в files/screenshots приложения на эмуляторе.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sources = TestData.byId(
        NASA_ID to "synd_single_video.json",
        "1849500000000000001" to "synd_multi_video.json",
        "1848400000000000123" to "synd_no_video.json",
    )

    private fun scheduler() = FakeScheduler().apply {
        flow.value = mapOf(
            "${NASA_ID}_1_1280x720" to DownloadState.Running(5L * 1024 * 1024, 12L * 1024 * 1024),
            "${NASA_ID}_1_640x360" to DownloadState.Done("content://media/external/video/media/1"),
            "${NASA_ID}_1_480x270" to DownloadState.Failed(ru.madhare74.xvideo.domain.AppError.DownloadFailed),
        )
    }

    private fun launch(fontScale: Float, dark: Boolean, vm: MainViewModel) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                XTheme(darkTheme = dark) { XApp(vm, readClipboard = { null }) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun searched(link: String): MainViewModel {
        val vm = viewModel(TestData.repository(sources), scheduler())
        vm.onInput(link)
        vm.search()
        return vm
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(300)
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun result_scale100_light() { launch(1.0f, false, searched(NASA_LINK)); save("result_scale100_light") }

    @Test fun result_scale100_dark() { launch(1.0f, true, searched(NASA_LINK)); save("result_scale100_dark") }

    @Test fun result_scale200_light() { launch(2.0f, false, searched(NASA_LINK)); save("result_scale200_light") }

    /** Прокрутка к строке качества, чтобы на снимке были варианты и состояния скачивания. */
    private fun scrollTo(variant: String) = composeRule.scrollIntoView(Tags.quality("${NASA_ID}_1_$variant"))

    @Test fun qualities_scale100_light() {
        launch(1.0f, false, searched(NASA_LINK)); scrollTo("480x270"); save("qualities_scale100_light")
    }

    @Test fun qualities_scale100_dark() {
        launch(1.0f, true, searched(NASA_LINK)); scrollTo("480x270"); save("qualities_scale100_dark")
    }

    @Test fun qualities_top_scale150_dark() {
        launch(1.5f, true, searched(NASA_LINK)); scrollTo("1280x720"); save("qualities_top_scale150_dark")
    }

    @Test fun qualities_bottom_scale150_light() {
        launch(1.5f, false, searched(NASA_LINK)); scrollTo("480x270"); save("qualities_bottom_scale150_light")
    }

    @Test fun qualities_top_scale200_dark() {
        launch(2.0f, true, searched(NASA_LINK)); scrollTo("1920x1080"); save("qualities_top_scale200_dark")
    }

    @Test fun qualities_mid_scale200_light() {
        launch(2.0f, false, searched(NASA_LINK)); scrollTo("1280x720"); save("qualities_mid_scale200_light")
    }

    @Test fun qualities_bottom_scale200_dark() {
        launch(2.0f, true, searched(NASA_LINK)); scrollTo("480x270"); save("qualities_bottom_scale200_dark")
    }

    @Test fun multi_scale150_light() {
        launch(1.5f, false, searched("https://x.com/test_channel/status/1849500000000000001"))
        save("multi_scale150_light")
    }

    @Test fun no_video_dark() {
        launch(1.0f, true, searched("https://x.com/photo_guy/status/1848400000000000123"))
        save("no_video_dark")
    }

    @Test fun start_clipboard_scale150_light() {
        val vm = viewModel(TestData.repository(sources))
        vm.onClipboard("https://x.com/NASA/status/1849012345678901234?s=46")
        launch(1.5f, false, vm)
        save("start_clipboard_scale150_light")
    }

    private fun restrictedVm(loggedIn: Boolean): MainViewModel {
        val session = fakeSession(loggedIn)
        val repository = PostRepository(
            TestData.syndication("synd_tombstone_age.json"), null, ShortLinkResolver { null },
            TestData.byId(NASA_ID to "gql_age.json"), { session.isLoggedIn },
        )
        return viewModel(repository, session = session).apply {
            onInput(NASA_LINK)
            search()
        }
    }

    @Test fun login_needed_scale100_dark() { launch(1.0f, true, restrictedVm(false)); save("login_needed_scale100_dark") }

    @Test fun login_needed_scale200_light() {
        launch(2.0f, false, restrictedVm(false))
        composeRule.scrollIntoView(Tags.LOGIN_FROM_ERROR)
        save("login_needed_scale200_light")
    }

    @Test fun restricted_for_account_scale150_dark() {
        launch(1.5f, true, restrictedVm(true))
        composeRule.scrollIntoView(Tags.RETRY_SEARCH)
        save("restricted_for_account_scale150_dark")
    }

    @Test fun restricted_for_account_scale200_light() {
        launch(2.0f, false, restrictedVm(true))
        composeRule.scrollIntoView(Tags.RETRY_SEARCH)
        save("restricted_for_account_scale200_light")
    }

    private fun historyVm(): MainViewModel {
        val history = TestData.tempHistory()
        history.add(HistoryItem("a", "content://media/external/video/media/1", "X_test_channel_1849500000000000001_2_720p.mp4", "Тестовый Канал", "test_channel", "1849500000000000001", 1280, 720, false, 1_792_560_600_000L))
        history.add(HistoryItem("b", "content://media/external/video/media/2", "X_devmemes_1848800000000000777_1_270p.mp4", "Dev Memes", "devmemes", "1848800000000000777", 480, 270, true, 1_792_564_200_000L))
        history.add(HistoryItem("c", "content://media/external/video/media/3", "X_NASA_${NASA_ID}_1_1080p.mp4", "NASA", "NASA", NASA_ID, 1920, 1080, false, 1_792_567_800_000L))
        val vm = viewModel(TestData.repository(sources), history = history, files = FakeFiles(setOf("content://media/external/video/media/2")))
        vm.selectTab(Tab.History)
        return vm
    }

    @Test fun history_scale100_light() { launch(1.0f, false, historyVm()); save("history_scale100_light") }

    @Test fun history_scale200_dark() { launch(2.0f, true, historyVm()); save("history_scale200_dark") }
}
