package ru.madhare74.xvideo.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.platform.LocalContext
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

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("login_ui", android.content.Context.MODE_PRIVATE) }
    // Подсказка открывается сама при первом входе, потом — по кнопке «?».
    var help by remember { mutableStateOf(!prefs.getBoolean("help_shown", false)) }

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
                    IconButton(onClick = { help = true }, modifier = Modifier.testTag(Tags.LOGIN_HELP)) {
                        Icon(Icons.Default.Info, contentDescription = "Как войти")
                    }
                    TextButton(onClick = { manual = true }, modifier = Modifier.testTag(Tags.LOGIN_MANUAL)) {
                        FitText("Токен")
                    }
                },
            )
        },
    ) { padding ->
        // imePadding: страница X сжимается над клавиатурой, и поле ввода остаётся видно.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
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

    if (help) {
        LoginHelpDialog(onDismiss = {
            help = false
            prefs.edit().putBoolean("help_shown", true).apply()
        })
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
                // Явно «на весь экран»: по умолчанию AndroidView создаёт WebView с высотой
                // «по содержимому», и тогда CSS 100vh/100dvh = 0 — окно входа X получается
                // нулевой высоты и форма не видна (чёрный или белый экран).
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // X не пускает «встроенные браузеры»: убираем пометку WebView из User-Agent.
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                // Автозаполнение логина и пароля: Google, Samsung Pass или другая служба,
                // выбранная в настройках телефона, предлагает сохранённый пароль от X.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                }
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

/** Как войти: по шагам, с подсказкой про автозаполнение паролей. */
@Composable
internal fun LoginHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(Tags.LOGIN_HELP_DIALOG),
        title = { FitText("Как войти в X") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("1. На странице X введите ник (без @), телефон или почту от аккаунта X и нажмите «Далее».")
                Text("2. Введите пароль от X и нажмите «Войти».")
                Text("3. Если X пришлёт код подтверждения — введите код из SMS или письма.")
                Text("4. После входа окно закроется само, и поиск видео повторится.")
                Text(
                    "Сохранённый пароль: нажмите на поле ввода — над клавиатурой появится подсказка Google " +
                        "или Samsung Pass. Если её нет: Настройки телефона → Общие настройки → Пароли и " +
                        "автозаполнение → Служба автозаполнения → выберите Samsung Pass или Google.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Кнопки «Войти через Google» и «Войти через Apple» здесь могут не работать. " +
                        "Пароль вводится на странице X, приложение его не видит.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { FitText("Понятно") } },
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
                    "Только если страница входа не открывается: войдите в x.com в Chrome на компьютере, откройте " +
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
