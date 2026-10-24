package ru.madhare74.xvideo.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.madhare74.xvideo.AppGraph
import ru.madhare74.xvideo.data.XCookies

/**
 * Вход в X: внутри приложения открывается обычная страница входа x.com. Пароль вводится
 * на странице X, приложение его не видит. Как только X выдаёт cookie сессии, приложение
 * сохраняет её и закрывает окно.
 */
class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = AppGraph.get(this).session
        setContent {
            XTheme {
                LoginScreen(
                    onCookies = { cookies ->
                        session.login(cookies)
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val LOGIN_URL = "https://x.com/i/flow/login"
        private val COOKIE_URLS = listOf("https://x.com", "https://twitter.com")

        /** Cookies сессии X из встроенного браузера, если вход уже выполнен. */
        fun webCookies(): XCookies? = try {
            val cm = CookieManager.getInstance()
            COOKIE_URLS.firstNotNullOfOrNull { XCookies.parse(cm.getCookie(it)) }
        } catch (e: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(onCookies: (XCookies) -> Unit, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var manual by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun finishWith(cookies: XCookies) {
        if (done) return
        done = true
        onCookies(cookies)
    }

    // Страница входа X многошаговая и не всегда перезагружается — проверяем cookies и по таймеру.
    LaunchedEffect(Unit) {
        while (!done) {
            LoginActivity.webCookies()?.let { finishWith(it) }
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { FitText("Вход в X") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { manual = true }, modifier = Modifier.testTag(Tags.LOGIN_MANUAL)) {
                        FitText("Ввести токен")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Войдите по логину, телефону или почте и паролю X. Пароль вводится на странице X, " +
                        "приложение его не видит. Вход через Google или Apple здесь может не работать.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                LoginWebView(
                    onCreated = { webView = it },
                    onLoading = {
                        loading = it
                        if (it) failed = false
                    },
                    onPageFinished = { LoginActivity.webCookies()?.let { finishWith(it) } },
                    onError = { failed = true },
                )
                if (failed) {
                    LoginErrorPanel(
                        onRetry = {
                            failed = false
                            webView?.loadUrl(LoginActivity.LOGIN_URL)
                        },
                        onManual = { manual = true },
                    )
                }
            }
        }
    }

    if (manual) {
        ManualTokenDialog(
            onDismiss = { manual = false },
            onSave = { cookies ->
                manual = false
                finishWith(cookies)
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    onCreated: (WebView) -> Unit,
    onLoading: (Boolean) -> Unit,
    onPageFinished: () -> Unit,
    onError: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // X не пускает «встроенные браузеры»: убираем пометку WebView из User-Agent.
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = onLoading(true)
                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoading(false)
                        onPageFinished()
                    }

                    // Страница x.com не открылась (нет сети, VPN не пропускает приложение) —
                    // вместо системной страницы ошибки показываем своё объяснение.
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            onLoading(false)
                            onError()
                        }
                    }
                }
                onCreated(this)
                loadUrl(LoginActivity.LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoginErrorPanel(onRetry: () -> Unit, onManual: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag(Tags.LOGIN_ERROR)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Страница входа X не открылась.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Сайт x.com недоступен для этого приложения. Чаще всего VPN работает только для " +
                    "части приложений. В приложении VPN откройте «Раздельное туннелирование» (или " +
                    "«Split tunneling», «Приложения») и добавьте «Загрузчик видео X» — или включите " +
                    "VPN для всех приложений. Проверить VPN можно, открыв x.com в Chrome.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, modifier = Modifier.testTag(Tags.LOGIN_RETRY)) { FitText("Повторить") }
                OutlinedButton(onClick = onManual) { FitText("Ввести токен") }
            }
        }
    }
}

/** Запасной способ входа: значение cookie `auth_token` из браузера на компьютере. */
@Composable
fun ManualTokenDialog(onDismiss: () -> Unit, onSave: (XCookies) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FitText("Ввести токен вручную") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Если страница входа не открывается: войдите в x.com в Chrome на компьютере, откройте " +
                        "«Инструменты разработчика» (F12) → «Application» → «Cookies» → https://x.com и " +
                        "скопируйте значение auth_token.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Токен даёт доступ к вашему аккаунту — никому его не передавайте. Приложение хранит его " +
                        "только на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = false
                    },
                    label = { FitText("auth_token") },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) {
                        { Text("Это не похоже на токен X: нужна строка из букв a–f и цифр.") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag(Tags.LOGIN_TOKEN_INPUT),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cookies = XCookies.fromManualInput(text)
                    if (cookies == null) error = true else onSave(cookies)
                },
                modifier = Modifier.testTag(Tags.LOGIN_TOKEN_SAVE),
            ) { FitText("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { FitText("Отмена") } },
    )
}
