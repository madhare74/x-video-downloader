package ru.madhare74.xvideo.ui

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.madhare74.xvideo.data.LookupResult
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.InMemorySessionStore
import ru.madhare74.xvideo.data.SizeProbe
import ru.madhare74.xvideo.data.XSession
import ru.madhare74.xvideo.domain.Messages
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.Post
import ru.madhare74.xvideo.domain.PostVideo
import ru.madhare74.xvideo.domain.VideoVariant
import ru.madhare74.xvideo.download.DownloadRequest
import ru.madhare74.xvideo.download.DownloadScheduler
import ru.madhare74.xvideo.download.DownloadState
import ru.madhare74.xvideo.history.DeleteResult
import ru.madhare74.xvideo.history.HistoryItem
import ru.madhare74.xvideo.history.HistoryStore
import ru.madhare74.xvideo.history.MediaFiles
import ru.madhare74.xvideo.link.LinkParser

enum class Tab { Search, History }

data class MainUiState(
    val input: String = "",
    val loading: Boolean = false,
    val post: Post? = null,
    val error: AppError? = null,
    /** Размеры файлов по ссылкам вариантов, если сервер их сообщил. */
    val sizes: Map<String, Long> = emptyMap(),
    /** Ссылка из буфера обмена, которую предлагаем подставить. */
    val clipboardLink: String? = null,
    val tab: Tab = Tab.Search,
    /** Записи истории, файлы которых удалены вне приложения. */
    val missingFiles: Set<String> = emptySet(),
    /** Выполнен ли вход в аккаунт X. */
    val loggedIn: Boolean = false,
)

sealed interface UiEvent {
    data class ConfirmDelete(val sender: IntentSender, val itemId: String) : UiEvent
    data class Message(val text: String) : UiEvent
}

class MainViewModel(
    private val repository: () -> PostRepository,
    private val sizeProbe: SizeProbe?,
    private val scheduler: DownloadScheduler,
    private val history: HistoryStore,
    private val files: MediaFiles,
    private val session: XSession = XSession(InMemorySessionStore()),
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState(loggedIn = session.isLoggedIn))
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    val downloads: StateFlow<Map<String, DownloadState>> =
        scheduler.states.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val historyItems: StateFlow<List<HistoryItem>> = history.items

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            session.cookies.collect { cookies -> _state.update { it.copy(loggedIn = cookies != null) } }
        }
    }

    /** Вход выполнен: если поиск остановился на «нужен вход», повторяем его. */
    fun onLoggedIn() {
        val error = _state.value.error
        if (session.isLoggedIn && error != null && Messages.canLogin(error) && _state.value.input.isNotBlank()) search()
    }

    fun logout() {
        session.logout()
        _events.tryEmit(UiEvent.Message("Вы вышли из аккаунта X"))
    }

    private var searchJob: Job? = null
    private var lastOfferedClipboard: String? = null

    fun onInput(text: String) = _state.update { it.copy(input = text) }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.History) refreshMissing()
    }

    /** Кнопка «Вставить»: подставляем текст из буфера обмена (ссылку из него, если она там есть). */
    fun paste(text: String?) {
        if (text.isNullOrBlank()) {
            _events.tryEmit(UiEvent.Message("Буфер обмена пуст"))
            return
        }
        val link = LinkParser.findLink(text) ?: text.trim()
        lastOfferedClipboard = link
        _state.update { it.copy(input = link, clipboardLink = null) }
    }

    /** Текст из «Поделиться»: подставляем и сразу ищем видео. */
    fun onShared(text: String) {
        val link = LinkParser.findLink(text) ?: text.trim()
        lastOfferedClipboard = link
        _state.update { it.copy(input = link, tab = Tab.Search, clipboardLink = null) }
        search()
    }

    /** Буфер обмена при открытии приложения: если там ссылка на X — предлагаем, но не подставляем молча. */
    fun onClipboard(text: String?) {
        val link = text?.let(LinkParser::findLink) ?: return
        val current = _state.value
        if (link == lastOfferedClipboard || link == LinkParser.findLink(current.input) || current.loading) return
        lastOfferedClipboard = link
        _state.update { it.copy(clipboardLink = link) }
    }

    fun acceptClipboard() {
        val link = _state.value.clipboardLink ?: return
        _state.update { it.copy(input = link, clipboardLink = null) }
        search()
    }

    fun dismissClipboard() = _state.update { it.copy(clipboardLink = null) }

    fun search() {
        val input = _state.value.input
        searchJob?.cancel()
        _state.update { it.copy(loading = true, error = null, post = null, sizes = emptyMap(), clipboardLink = null) }
        searchJob = viewModelScope.launch {
            when (val result = repository().lookup(input)) {
                is LookupResult.Success -> {
                    _state.update { it.copy(loading = false, post = result.post) }
                    loadSizes(result.post)
                }
                is LookupResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    private suspend fun loadSizes(post: Post) {
        val probe = sizeProbe ?: return
        val urls = post.videos.flatMap { v -> v.variants.map { it.url } }
        val sizes = withContext(Dispatchers.IO) {
            urls.map { url -> async { url to probe.size(url) } }.awaitAll()
        }.mapNotNull { (url, size) -> size?.let { url to it } }.toMap()
        if (_state.value.post == post) _state.update { it.copy(sizes = sizes) }
    }

    fun download(video: PostVideo, variant: VideoVariant) {
        val post = _state.value.post ?: return
        scheduler.enqueue(
            DownloadRequest(
                key = DownloadRequest.key(post.id, video.index, variant),
                url = variant.url,
                postId = post.id,
                screenName = post.screenName,
                authorName = post.authorName,
                videoIndex = video.index,
                width = variant.width,
                height = variant.height,
                isGif = video.isGif,
            ),
        )
    }

    fun cancel(key: String) = scheduler.cancel(key)

    fun refreshMissing() {
        viewModelScope.launch {
            val items = history.items.value
            val missing = withContext(Dispatchers.IO) {
                items.filterNot { files.exists(it.uri) }.map { it.id }.toSet()
            }
            _state.update { it.copy(missingFiles = missing) }
        }
    }

    fun deleteFile(item: HistoryItem) {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { files.delete(item.uri) }) {
                DeleteResult.Deleted -> history.remove(item.id)
                is DeleteResult.NeedsConfirmation -> _events.tryEmit(UiEvent.ConfirmDelete(r.sender, item.id))
                DeleteResult.Failed -> _events.tryEmit(UiEvent.Message("Не удалось удалить файл"))
            }
        }
    }

    /** Пользователь подтвердил удаление в системном окне. */
    fun onDeleteConfirmed(itemId: String) = history.remove(itemId)

    fun removeEntry(itemId: String) = history.remove(itemId)

    class Factory(
        private val repository: () -> PostRepository,
        private val sizeProbe: SizeProbe?,
        private val scheduler: DownloadScheduler,
        private val history: HistoryStore,
        private val files: MediaFiles,
        private val session: XSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository, sizeProbe, scheduler, history, files, session) as T
    }
}
