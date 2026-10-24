package ru.madhare74.xvideo

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.madhare74.xvideo.history.AndroidMediaFiles
import ru.madhare74.xvideo.ui.MainViewModel
import ru.madhare74.xvideo.ui.XApp
import ru.madhare74.xvideo.ui.XTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val graph = AppGraph.get(this)
        MainViewModel.Factory(
            repository = { graph.repository },
            sizeProbe = graph.sizeProbe,
            scheduler = graph.scheduler,
            history = graph.history,
            files = AndroidMediaFiles(applicationContext, graph.saver),
            session = graph.session,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            XTheme {
                XApp(viewModel = viewModel, readClipboard = ::clipboardText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Ссылка из «Поделиться» в приложении X. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) viewModel.onShared(text)
        // Чтобы при повороте экрана ссылка не обрабатывалась повторно.
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** Буфер обмена читается, когда окно в фокусе — иначе Android 10+ его не отдаёт. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Читаем буфер только когда в нём появилось что-то новое: Android 12+ показывает
        // уведомление о каждом чтении.
        val stamp = try {
            getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.timestamp
        } catch (e: Exception) {
            null
        } ?: return
        if (stamp == lastClipStamp) return
        lastClipStamp = stamp
        viewModel.onClipboard(clipboardText())
    }

    private var lastClipStamp: Long = -1

    private fun clipboardText(): String? = try {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this)?.toString()
    } catch (e: Exception) {
        null
    }
}
