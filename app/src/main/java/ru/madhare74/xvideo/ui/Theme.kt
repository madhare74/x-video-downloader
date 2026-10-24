package ru.madhare74.xvideo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1D9BF0)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6BB0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8FB),
    onPrimaryContainer = Color(0xFF00223D),
    secondaryContainer = Color(0xFFE3EAF2),
    background = Color(0xFFFAFBFC),
    surface = Color(0xFFFAFBFC),
    surfaceVariant = Color(0xFFE6EBF0),
)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF001E33),
    primaryContainer = Color(0xFF0D3A5C),
    onPrimaryContainer = Color(0xFFD3E8FB),
    secondaryContainer = Color(0xFF26313B),
    background = Color(0xFF0E1114),
    surface = Color(0xFF0E1114),
    surfaceVariant = Color(0xFF232A31),
)

@Composable
fun XTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/** Метки элементов для UI-тестов. */
object Tags {
    const val INPUT = "input"
    const val PASTE = "paste"
    const val SEARCH = "search"
    const val LOADING = "loading"
    const val ERROR = "error"
    const val RETRY_SEARCH = "retry_search"
    const val AUTHOR = "author"
    const val CLIPBOARD = "clipboard"
    const val CLIPBOARD_ACCEPT = "clipboard_accept"
    const val TAB_SEARCH = "tab_search"
    const val TAB_HISTORY = "tab_history"
    const val HISTORY_LIST = "history_list"
    const val HISTORY_EMPTY = "history_empty"
    const val RESULT_LIST = "result_list"
    const val LOGIN_FROM_ERROR = "login_from_error"
    const val ACCOUNT = "account"
    const val ACCOUNT_LOGIN = "account_login"
    const val ACCOUNT_LOGOUT = "account_logout"
    const val ACCOUNT_STATUS = "account_status"
    const val LOGIN_MANUAL = "login_manual"
    const val LOGIN_ERROR = "login_error"
    const val LOGIN_HELP = "login_help"
    const val LOGIN_HELP_DIALOG = "login_help_dialog"
    const val LOGIN_RETRY = "login_retry"
    const val LOGIN_TOKEN_INPUT = "login_token_input"
    const val LOGIN_TOKEN_SAVE = "login_token_save"

    fun video(index: Int) = "video_$index"
    fun quality(key: String) = "quality_$key"
    fun download(key: String) = "download_$key"
    fun downloadState(key: String) = "download_state_$key"
    fun historyItem(fileName: String) = "history_$fileName"
}
