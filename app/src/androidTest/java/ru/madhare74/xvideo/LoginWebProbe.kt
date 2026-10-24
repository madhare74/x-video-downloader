package ru.madhare74.xvideo

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Диагностика: открывает настоящую страницу входа x.com во встроенном браузере разными
 * способами и сохраняет снимок экрана и журнал страницы. Нужна сеть, поэтому в обычном
 * прогоне пропускается; CI запускает её отдельно с аргументом probe=true.
 */
@RunWith(AndroidJUnit4::class)
class LoginWebProbe {

    @get:Rule
    val composeRule = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.filesDir, "screenshots").apply { mkdirs() }

    private val mobileChrome =
        "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val desktopChrome =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    private fun probe(name: String, url: String, userAgent: ((String) -> String)?, chromeClient: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probe") == "true")
        val log = Collections.synchronizedList(mutableListOf<String>())
        var web: WebView? = null
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    userAgent?.let { settings.userAgentString = it(settings.userAgentString) }
                    log += "UA: ${settings.userAgentString}"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) { log += "start $u" }
                        override fun onPageFinished(view: WebView?, u: String?) { log += "finish $u" }
                        override fun onReceivedError(view: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                            log += "error main=${r?.isForMainFrame} ${r?.url} ${e?.errorCode} ${e?.description}"
                        }
                    }
                    if (chromeClient) {
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                log += "console ${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})"
                                return true
                            }
                        }
                    }
                    web = this
                    loadUrl(url)
                }
            })
        }
        Thread.sleep(25_000)
        composeRule.waitForIdle()
        val text = java.util.concurrent.CountDownLatch(1)
        composeRule.runOnUiThread {
            web?.evaluateJavascript(
                "(function(){return location.href+' | '+document.title+' | inputs='+document.querySelectorAll('input').length+' | '+(document.body?document.body.innerText.slice(0,600):'')})()",
            ) { v ->
                log += "page: $v"
                text.countDown()
            }
        }
        text.await(5, java.util.concurrent.TimeUnit.SECONDS)
        log += "cookies: " + (CookieManager.getInstance().getCookie("https://x.com")?.split(';')?.map { it.substringBefore('=').trim() } ?: "none")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { b ->
            File(dir, "probe_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "probe_$name.txt").writeText(log.joinToString("\n"))
        composeRule.runOnUiThread { CookieManager.getInstance().removeAllCookies(null) }
    }

    @Test fun a_current() = probe("a_current_flow", "https://x.com/i/flow/login", { it.replace("; wv", "") }, chromeClient = false)

    @Test fun b_current_console() = probe("b_current_console", "https://x.com/i/flow/login", { it.replace("; wv", "") }, chromeClient = true)

    @Test fun c_default_ua() = probe("c_default_ua", "https://x.com/i/flow/login", null, chromeClient = true)

    @Test fun d_mobile_chrome() = probe("d_mobile_chrome", "https://x.com/i/flow/login", { mobileChrome }, chromeClient = true)

    @Test fun e_mobile_chrome_login() = probe("e_mobile_chrome_login", "https://x.com/login", { mobileChrome }, chromeClient = true)

    @Test fun f_desktop_chrome() = probe("f_desktop_chrome", "https://x.com/i/flow/login", { desktopChrome }, chromeClient = true)
}
