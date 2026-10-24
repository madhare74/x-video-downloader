package ru.madhare74.xvideo

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.ShortLinkResolver
import ru.madhare74.xvideo.ui.ManualTokenDialog
import ru.madhare74.xvideo.ui.Tags
import ru.madhare74.xvideo.ui.XApp
import ru.madhare74.xvideo.ui.XTheme
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Снимки меню и диалогов при настоящем системном размере шрифта. Меню и диалоги — отдельные
 * окна, подмена шрифта внутри теста на них не действует, поэтому CI запускает этот класс
 * ещё раз после `settings put system font_scale 2.0`. Масштаб попадает в имя файла.
 */
@RunWith(AndroidJUnit4::class)
class SystemFontScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val scale = (ctx.resources.configuration.fontScale * 100).roundToInt()

    private fun saveScreen(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(600)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.filesDir, "screenshots").apply { mkdirs() }
        File(dir, String.format(Locale.ROOT, "sys%d_%s.png", scale, name)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private val sources = TestData.byId(NASA_ID to "synd_single_video.json")

    @Test fun account_menu_logged_out_dark() {
        val vm = viewModel(TestData.repository(sources), session = fakeSession(false))
        composeRule.setContent { XTheme(darkTheme = true) { XApp(vm, readClipboard = { null }) } }
        composeRule.onNodeWithTag(Tags.ACCOUNT).performClick()
        saveScreen("account_menu_logged_out_dark")
    }

    @Test fun account_menu_logged_in_light() {
        val vm = viewModel(TestData.repository(sources), session = fakeSession(true))
        composeRule.setContent { XTheme(darkTheme = false) { XApp(vm, readClipboard = { null }) } }
        composeRule.onNodeWithTag(Tags.ACCOUNT).performClick()
        saveScreen("account_menu_logged_in_light")
    }

    @Test fun token_dialog_dark() {
        composeRule.setContent { XTheme(darkTheme = true) { ManualTokenDialog(onDismiss = {}, onSave = {}) } }
        saveScreen("token_dialog_dark")
    }

    @Test fun login_page_error_dark() {
        composeRule.setContent { XTheme(darkTheme = true) { ru.madhare74.xvideo.ui.LoginErrorPanel(onRetry = {}, onManual = {}) } }
        saveScreen("login_page_error_dark")
    }

    @Test fun login_needed_light() {
        val session = fakeSession(false)
        val repository = PostRepository(
            TestData.syndication("synd_tombstone_age.json"), null, ShortLinkResolver { null },
            TestData.byId(NASA_ID to "gql_age.json"), { session.isLoggedIn },
        )
        val vm = viewModel(repository, session = session).apply { onInput(NASA_LINK); search() }
        composeRule.setContent { XTheme(darkTheme = false) { XApp(vm, readClipboard = { null }) } }
        composeRule.scrollIntoView(Tags.LOGIN_FROM_ERROR)
        saveScreen("login_needed_light")
    }
}
