package ru.madhare74.xvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.madhare74.xvideo.domain.RuFormat
import ru.madhare74.xvideo.history.HistoryItem

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    items: List<HistoryItem>,
    missing: Set<String>,
    modifier: Modifier = Modifier,
    onOpen: (HistoryItem) -> Unit,
    onShare: (HistoryItem) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onRemoveEntry: (String) -> Unit,
) {
    var confirm by remember { mutableStateOf<HistoryItem?>(null) }

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp).testTag(Tags.HISTORY_EMPTY), contentAlignment = Alignment.Center) {
            Text(
                "Здесь появятся скачанные видео.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(Tags.HISTORY_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val gone = item.id in missing
            Card(Modifier.fillMaxWidth().testTag(Tags.historyItem(item.fileName))) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(width = 112.dp, height = 63.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black),
                    ) {
                        if (!gone) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            item.authorName.ifBlank { "@${item.screenName}" },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("@${item.screenName}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val quality = buildList {
                            if (item.width > 0 && item.height > 0) add("${item.width}×${item.height}")
                            if (item.isGif) add("GIF")
                        }.joinToString(" · ")
                        if (quality.isNotEmpty()) Text(quality, style = MaterialTheme.typography.bodySmall)
                        Text(RuFormat.dateTime(item.savedAt), style = MaterialTheme.typography.bodySmall)
                        if (gone) {
                            Text("Файл удалён", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                FlowRow(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                    if (gone) {
                        TextButton(onClick = { onRemoveEntry(item.id) }) { Text("Убрать из списка") }
                    } else {
                        TextButton(onClick = { onOpen(item) }) { Text("Открыть") }
                        TextButton(onClick = { onShare(item) }) { Text("Поделиться") }
                        TextButton(onClick = { confirm = item }) { Text("Удалить") }
                    }
                }
            }
        }
    }

    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Удалить видео?") },
            text = { Text("Файл ${item.fileName} будет удалён с телефона.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onDelete(item)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}
