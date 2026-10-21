package ru.madhare74.xvideo.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** Запись истории загрузок. [uri] — content://-адрес файла в MediaStore. */
data class HistoryItem(
    val id: String,
    val uri: String,
    val fileName: String,
    val authorName: String,
    val screenName: String,
    val postId: String,
    val width: Int,
    val height: Int,
    val isGif: Boolean,
    val savedAt: Long,
)

/** История загрузок в JSON-файле на устройстве. Новые записи — сверху. */
class HistoryStore(private val file: File) {

    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    fun add(item: HistoryItem) = update { listOf(item) + it.filterNot { old -> old.id == item.id } }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    private fun update(change: (List<HistoryItem>) -> List<HistoryItem>) {
        synchronized(lock) {
            val next = change(_items.value)
            save(next)
            _items.value = next
        }
    }

    private fun load(): List<HistoryItem> {
        if (!file.exists()) return emptyList()
        return try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptyList()
            root.mapNotNull { (it as? JsonObject)?.let(::fromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(items: List<HistoryItem>) {
        val json = buildJsonArray { items.forEach { add(toJson(it)) } }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }

    private fun toJson(i: HistoryItem) = buildJsonObject {
        put("id", i.id)
        put("uri", i.uri)
        put("fileName", i.fileName)
        put("authorName", i.authorName)
        put("screenName", i.screenName)
        put("postId", i.postId)
        put("width", i.width)
        put("height", i.height)
        put("isGif", i.isGif)
        put("savedAt", i.savedAt)
    }

    private fun fromJson(o: JsonObject): HistoryItem? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val uri = s("uri") ?: return null
        return HistoryItem(
            id = s("id") ?: uri,
            uri = uri,
            fileName = s("fileName").orEmpty(),
            authorName = s("authorName").orEmpty(),
            screenName = s("screenName").orEmpty(),
            postId = s("postId").orEmpty(),
            width = o["width"]?.jsonPrimitive?.intOrNull ?: 0,
            height = o["height"]?.jsonPrimitive?.intOrNull ?: 0,
            isGif = o["isGif"]?.jsonPrimitive?.booleanOrNull ?: false,
            savedAt = o["savedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
}
