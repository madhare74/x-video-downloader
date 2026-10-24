package ru.madhare74.xvideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.madhare74.xvideo.data.XCookies
import ru.madhare74.xvideo.ui.ManualTokenDialog
import ru.madhare74.xvideo.ui.Tags
import ru.madhare74.xvideo.ui.XTheme

/** Ручной ввод токена (запасной способ входа). Страница входа X в тестах не открывается. */
@RunWith(AndroidJUnit4::class)
class LoginUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manualTokenValidation() {
        var saved: XCookies? = null
        composeRule.setContent { XTheme { ManualTokenDialog(onDismiss = {}, onSave = { saved = it }) } }
        composeRule.onNodeWithTag(Tags.LOGIN_TOKEN_INPUT).performTextInput("пароль123")
        composeRule.onNodeWithTag(Tags.LOGIN_TOKEN_SAVE).performClick()
        composeRule.onNodeWithText("Это не похоже на токен X: нужна строка из букв a–f и цифр.").assertIsDisplayed()
        assertNull(saved)

        composeRule.onNodeWithTag(Tags.LOGIN_TOKEN_INPUT).performTextReplacement("auth_token=0123456789abcdef0123456789abcdef01234567")
        composeRule.onNodeWithTag(Tags.LOGIN_TOKEN_SAVE).performClick()
        composeRule.waitForIdle()
        assertEquals("0123456789abcdef0123456789abcdef01234567", saved?.authToken)
    }
}
