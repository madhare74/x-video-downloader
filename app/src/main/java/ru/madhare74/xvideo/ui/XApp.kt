package ru.madhare74.xvideo.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.madhare74.xvideo.R

/** Корневой экран: вкладки «Скачать» и «История». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XApp(viewModel: MainViewModel, readClipboard: () -> String?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val history by viewModel.historyItems.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun message(text: String) = scope.launch { snackbar.showSnackbar(text) }

    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = pendingDeleteId
        pendingDeleteId = null
        if (result.resultCode == android.app.Activity.RESULT_OK && id != null) viewModel.onDeleteConfirmed(id)
    }

    // Вход в X: после успешного входа повторяем поиск, если он остановился на «нужен вход».
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            message("Вход в X выполнен")
            viewModel.onLoggedIn()
        }
    }
    val startLogin = { loginLauncher.launch(Intent(context, LoginActivity::class.java)) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Message -> message(event.text)
                is UiEvent.ConfirmDelete -> {
                    pendingDeleteId = event.itemId
                    deleteLauncher.launch(IntentSenderRequest.Builder(event.sender).build())
                }
            }
        }
    }

    // Разрешения запрашиваются при первом скачивании: уведомления (Android 13+)
    // и доступ к памяти (только Android 8–9).
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val action = pendingAction
        pendingAction = null
        if (results[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false) {
            message("Без доступа к памяти видео не сохранить")
        } else {
            action?.invoke()
        }
    }
    fun withPermissions(action: () -> Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
                !context.granted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !context.granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needed.isEmpty()) {
            action()
        } else {
            askedNotifications = true
            pendingAction = action
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    FitText(if (state.tab == Tab.Search) "Загрузчик видео X" else "История загрузок")
                },
                actions = {
                    AccountMenu(loggedIn = state.loggedIn, onLogin = startLogin, onLogout = viewModel::logout)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.tab == Tab.Search,
                    onClick = { viewModel.selectTab(Tab.Search) },
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    label = { FitText("Скачать") },
                    modifier = Modifier.testTag(Tags.TAB_SEARCH),
                )
                NavigationBarItem(
                    selected = state.tab == Tab.History,
                    onClick = { viewModel.selectTab(Tab.History) },
                    icon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                    label = { FitText("История") },
                    modifier = Modifier.testTag(Tags.TAB_HISTORY),
                )
            }
        },
    ) { padding ->
        when (state.tab) {
            Tab.Search -> SearchScreen(
                state = state,
                downloads = downloads,
                modifier = Modifier.padding(padding),
                onInput = viewModel::onInput,
                onPaste = { viewModel.paste(readClipboard()) },
                onSearch = viewModel::search,
                onAcceptClipboard = viewModel::acceptClipboard,
                onDismissClipboard = viewModel::dismissClipboard,
                onDownload = { video, variant -> withPermissions { viewModel.download(video, variant) } },
                onCancel = viewModel::cancel,
                onOpen = { uri -> if (!context.openVideo(uri)) message("Нет приложения для просмотра видео") },
                onLogin = startLogin,
            )
            Tab.History -> HistoryScreen(
                items = history,
                missing = state.missingFiles,
                modifier = Modifier.padding(padding),
                onOpen = { item -> if (!context.openVideo(item.uri)) message("Нет приложения для просмотра видео") },
                onShare = { item -> context.shareVideo(item.uri) },
                onDelete = viewModel::deleteFile,
                onRemoveEntry = viewModel::removeEntry,
            )
        }
    }
}

/** Кнопка «Аккаунт X» в шапке: войти или выйти. */
@Composable
fun AccountMenu(loggedIn: Boolean, onLogin: () -> Unit, onLogout: () -> Unit, initiallyOpen: Boolean = false) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(Tags.ACCOUNT)) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = if (loggedIn) "Аккаунт X: вход выполнен" else "Аккаунт X: войти",
                tint = if (loggedIn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (loggedIn) {
                DropdownMenuItem(
                    text = { FitText("Вход в X выполнен", color = MaterialTheme.colorScheme.primary) },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag(Tags.ACCOUNT_STATUS),
                )
                DropdownMenuItem(
                    text = { FitText("Выйти из X") },
                    onClick = {
                        open = false
                        onLogout()
                    },
                    modifier = Modifier.testTag(Tags.ACCOUNT_LOGOUT),
                )
            } else {
                DropdownMenuItem(
                    text = { FitText("Вход в X не выполнен", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag(Tags.ACCOUNT_STATUS),
                )
                DropdownMenuItem(
                    text = { FitText("Войти в X") },
                    onClick = {
                        open = false
                        onLogin()
                    },
                    modifier = Modifier.testTag(Tags.ACCOUNT_LOGIN),
                )
            }
        }
    }
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.openVideo(uri: String): Boolean = try {
    startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

fun Context.shareVideo(uri: String) {
    val u = Uri.parse(uri)
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, u)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", u)
    try {
        startActivity(Intent.createChooser(send, "Поделиться видео").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Некуда отправить.
    }
}
