package ru.madhare74.xvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.madhare74.xvideo.R
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.Messages
import ru.madhare74.xvideo.domain.Post
import ru.madhare74.xvideo.domain.PostVideo
import ru.madhare74.xvideo.domain.RuFormat
import ru.madhare74.xvideo.domain.VideoVariant
import ru.madhare74.xvideo.download.DownloadRequest
import ru.madhare74.xvideo.download.DownloadState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: MainUiState,
    downloads: Map<String, DownloadState>,
    modifier: Modifier = Modifier,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onSearch: () -> Unit,
    onAcceptClipboard: () -> Unit,
    onDismissClipboard: () -> Unit,
    onDownload: (PostVideo, VideoVariant) -> Unit,
    onCancel: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(Tags.RESULT_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "input") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth().testTag(Tags.INPUT),
                    label = { Text("Ссылка на пост X") },
                    placeholder = { Text("https://x.com/…/status/…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    trailingIcon = {
                        if (state.input.isNotEmpty()) {
                            IconButton(onClick = { onInput("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onPaste, modifier = Modifier.testTag(Tags.PASTE)) {
                        Icon(painterResource(R.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        FitText("Вставить")
                    }
                    Button(
                        onClick = onSearch,
                        enabled = state.input.isNotBlank() && !state.loading,
                        modifier = Modifier.testTag(Tags.SEARCH),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        FitText("Найти видео")
                    }
                }
            }
        }

        state.clipboardLink?.let { link ->
            item(key = "clipboard") { ClipboardCard(link, onAcceptClipboard, onDismissClipboard) }
        }

        if (state.loading) {
            item(key = "loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(Tags.LOADING),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Ищем видео…")
                }
            }
        }

        state.error?.let { error ->
            item(key = "error") { ErrorCard(error, onRetry = onSearch) }
        }

        state.post?.let { post ->
            item(key = "author") { AuthorHeader(post) }
            items(post.videos, key = { "video_${it.index}" }) { video ->
                VideoCard(
                    post = post,
                    video = video,
                    total = post.videos.size,
                    sizes = state.sizes,
                    downloads = downloads,
                    onDownload = onDownload,
                    onCancel = onCancel,
                    onOpen = onOpen,
                )
            }
        }

        if (state.post == null && state.error == null && !state.loading && state.clipboardLink == null) {
            item(key = "hint") {
                Text(
                    "Вставьте ссылку на пост X или нажмите «Поделиться» → «Загрузчик видео X» в приложении X.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipboardCard(link: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(Tags.CLIPBOARD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FitText("В буфере обмена ссылка на X", fontWeight = FontWeight.SemiBold)
            Text(link, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, modifier = Modifier.testTag(Tags.CLIPBOARD_ACCEPT)) { FitText("Подставить") }
                TextButton(onClick = onDismiss) { FitText("Не надо") }
            }
        }
    }
}

@Composable
private fun ErrorCard(error: AppError, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(Tags.ERROR),
        colors = CardDefaults.cardColors(
            containerColor = if (error == AppError.NoVideo) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Messages.text(error), style = MaterialTheme.typography.bodyLarge)
            if (Messages.canRetrySearch(error)) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag(Tags.RETRY_SEARCH)) { FitText("Повторить") }
            }
        }
    }
}

@Composable
private fun AuthorHeader(post: Post) {
    Column(Modifier.fillMaxWidth().testTag(Tags.AUTHOR), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FitText(post.authorName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FitText("@${post.screenName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (post.text.isNotBlank()) {
            Text(post.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VideoCard(
    post: Post,
    video: PostVideo,
    total: Int,
    sizes: Map<String, Long>,
    downloads: Map<String, DownloadState>,
    onDownload: (PostVideo, VideoVariant) -> Unit,
    onCancel: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(Tags.video(video.index))) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            if (video.thumbnailUrl != null) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = "Превью видео ${video.index}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (video.isGif) Badge("GIF")
                video.durationMs?.let { Badge(RuFormat.duration(it)) }
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val kind = if (video.isGif) "GIF" else "Видео"
            FitText(
                if (total > 1) "$kind ${video.index} из $total" else kind,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            video.variants.forEachIndexed { i, variant ->
                val key = DownloadRequest.key(post.id, video.index, variant)
                QualityRow(
                    variant = variant,
                    best = i == 0,
                    size = sizes[variant.url],
                    downloadKey = key,
                    state = downloads[key],
                    onDownload = { onDownload(video, variant) },
                    onCancel = { onCancel(key) },
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    FitText(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityRow(
    variant: VideoVariant,
    best: Boolean,
    size: Long?,
    downloadKey: String,
    state: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (best) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(Tags.quality(downloadKey)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // При крупном шрифте кнопка переносится под описание, а не сжимает его.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)) {
                    FitText(
                        variant.resolution + if (variant.height > 0) " · ${variant.height}p" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    val details = listOfNotNull(
                        if (best) "Лучшее качество" else null,
                        size?.let { RuFormat.megabytes(it) },
                    )
                    if (details.isNotEmpty()) {
                        FitText(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val busy = state is DownloadState.Running || state is DownloadState.Queued
                if (!busy) {
                    val label = if (state is DownloadState.Done) "Ещё раз" else "Скачать"
                    if (best && state !is DownloadState.Done) {
                        Button(onClick = onDownload, modifier = Modifier.align(Alignment.CenterVertically).testTag(Tags.download(downloadKey))) { FitText(label) }
                    } else {
                        OutlinedButton(onClick = onDownload, modifier = Modifier.align(Alignment.CenterVertically).testTag(Tags.download(downloadKey))) { FitText(label) }
                    }
                }
            }
            when (state) {
                is DownloadState.Queued, is DownloadState.Running -> {
                    val running = state as? DownloadState.Running
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(Tags.downloadState(downloadKey))) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val total = running?.total
                            if (running != null && total != null) {
                                LinearProgressIndicator(
                                    progress = { (running.done.toFloat() / total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            FitText(
                                if (running == null || (running.done == 0L && running.total == null)) "Ожидание…"
                                else RuFormat.progress(running.done, running.total),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Отменить")
                        }
                    }
                }
                is DownloadState.Done -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag(Tags.downloadState(downloadKey)),
                ) {
                    FitText("Сохранено", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { onOpen(state.uri) }) { FitText("Открыть") }
                }
                is DownloadState.Failed -> Column(
                    Modifier.testTag(Tags.downloadState(downloadKey)),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(Messages.text(state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDownload, contentPadding = PaddingValues(0.dp)) { FitText("Повторить") }
                }
                DownloadState.Cancelled, null -> Unit
            }
        }
    }
}
