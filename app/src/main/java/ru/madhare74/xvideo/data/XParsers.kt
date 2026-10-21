package ru.madhare74.xvideo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.madhare74.xvideo.domain.Post
import ru.madhare74.xvideo.domain.PostVideo
import ru.madhare74.xvideo.domain.Qualities
import ru.madhare74.xvideo.domain.RawVariant

/** Итог запроса информации о посте у одного источника. */
sealed interface FetchOutcome {
    data class Found(val post: Post) : FetchOutcome
    data object NotFound : FetchOutcome
    /** Закрытый аккаунт или возрастное ограничение — нужен вход в аккаунт. */
    data object Restricted : FetchOutcome
    /** Ответ не похож на ожидаемый: X изменил формат. */
    data object BadFormat : FetchOutcome
}

/** Внутренний сигнал «структура ответа не та» — превращается в [FetchOutcome.BadFormat]. */
internal class FormatException(message: String) : Exception(message)

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal fun parseRoot(body: String): JsonElement? =
    try {
        lenientJson.parseToJsonElement(body)
    } catch (e: Exception) {
        null
    }

/**
 * Разбор медиа в формате X «media»: одинаков в сервисе встраивания (`mediaDetails`)
 * и в гостевом API (`legacy.extended_entities.media`). Фото пропускаются.
 */
internal object MediaParser {

    fun videos(media: JsonArray?): List<PostVideo> {
        if (media == null) return emptyList()
        val result = mutableListOf<PostVideo>()
        for (item in media) {
            val type = item["type"].str() ?: throw FormatException("media.type")
            if (type != "video" && type != "animated_gif") continue
            val info = item["video_info"].obj()
            val availability = item["ext_media_availability"]["status"].str()
            val raw = info?.get("variants").arr()?.mapNotNull { v ->
                val url = v["url"].str() ?: return@mapNotNull null
                RawVariant(url = url, contentType = v["content_type"].str(), bitrate = v["bitrate"].long())
            }
            if (raw == null && availability == null) throw FormatException("media.video_info")
            val width = item["original_info"]["width"].long()?.toInt() ?: 0
            val height = item["original_info"]["height"].long()?.toInt() ?: 0
            result += PostVideo(
                index = result.size + 1,
                thumbnailUrl = item["media_url_https"].str(),
                durationMs = info?.get("duration_millis").long()?.takeIf { it > 0 },
                isGif = type == "animated_gif",
                variants = Qualities.select(raw.orEmpty(), width, height),
            )
        }
        return result
    }
}

/** Убирает из текста поста служебные короткие ссылки на вложения в конце. */
internal fun cleanText(text: String): String =
    text.replace(Regex("""(\s*https://t\.co/\w+)+\s*$"""), "").trim()

/** Классификация «надгробия» (пост скрыт) по его тексту. */
internal fun classifyTombstone(text: String?): FetchOutcome {
    val t = text.orEmpty().lowercase()
    val gone = listOf("deleted", "doesn’t exist", "doesn't exist", "no longer", "suspended", "unavailable")
    val restricted = listOf("limits who can view", "protected", "age-restricted", "adult", "sensitive")
    return if (gone.any { it in t } && restricted.none { it in t }) FetchOutcome.NotFound
    else FetchOutcome.Restricted
}

/**
 * Ответ публичного сервиса встраивания постов X:
 * `https://cdn.syndication.twimg.com/tweet-result?id=…&token=…`.
 */
object SyndicationParser {

    fun parse(body: String): FetchOutcome {
        if (body.isBlank()) return FetchOutcome.NotFound
        val root = parseRoot(body).obj() ?: return FetchOutcome.BadFormat
        if (root.isEmpty()) return FetchOutcome.NotFound
        return try {
            when (root["__typename"].str()) {
                "Tweet" -> FetchOutcome.Found(tweet(root))
                "TweetTombstone" -> classifyTombstone(root["tombstone"]["text"]["text"].str())
                "TweetUnavailable" -> FetchOutcome.Restricted
                null -> if (root["id_str"] != null && root["user"] != null) FetchOutcome.Found(tweet(root)) else FetchOutcome.BadFormat
                else -> FetchOutcome.BadFormat
            }
        } catch (e: FormatException) {
            FetchOutcome.BadFormat
        }
    }

    private fun tweet(root: JsonObject): Post {
        val id = root["id_str"].str() ?: throw FormatException("id_str")
        val user = root["user"].obj() ?: throw FormatException("user")
        val screenName = user["screen_name"].str() ?: throw FormatException("screen_name")
        var videos = MediaParser.videos(root["mediaDetails"].arr())
        if (videos.isEmpty()) {
            // Видео может быть в процитированном посте.
            videos = MediaParser.videos(root["quoted_tweet"]["mediaDetails"].arr())
        }
        return Post(
            id = id,
            authorName = user["name"].str() ?: screenName,
            screenName = screenName,
            text = cleanText(root["text"].str().orEmpty()),
            videos = videos,
        )
    }
}

/** Ответ гостевого API X (GraphQL `TweetResultByRestId`) — запасной способ. */
object GuestApiParser {

    fun parse(body: String): FetchOutcome {
        val root = parseRoot(body).obj() ?: return FetchOutcome.BadFormat
        val data = root["data"].obj() ?: return FetchOutcome.BadFormat
        val result = data["tweetResult"]["result"].obj() ?: return FetchOutcome.NotFound
        return try {
            outcome(result)
        } catch (e: FormatException) {
            FetchOutcome.BadFormat
        }
    }

    private fun outcome(result: JsonObject): FetchOutcome = when (result["__typename"].str()) {
        "Tweet" -> FetchOutcome.Found(tweet(result))
        "TweetWithVisibilityResults" ->
            FetchOutcome.Found(tweet(result["tweet"].obj() ?: throw FormatException("tweet")))
        "TweetTombstone" -> classifyTombstone(result["tombstone"]["text"]["text"].str())
        "TweetUnavailable" -> when (result["reason"].str()) {
            "Protected", "NsfwLoggedOut", "NsfwViewerHasNoStatedAge", "NsfwViewerIsUnderage" -> FetchOutcome.Restricted
            else -> FetchOutcome.NotFound
        }
        else -> FetchOutcome.BadFormat
    }

    private fun tweet(t: JsonObject): Post {
        val legacy = t["legacy"].obj() ?: throw FormatException("legacy")
        val id = legacy["id_str"].str() ?: t["rest_id"].str() ?: throw FormatException("id")
        val user = t["core"]["user_results"]["result"]
        val screenName = user["core"]["screen_name"].str() ?: user["legacy"]["screen_name"].str()
            ?: throw FormatException("screen_name")
        val name = user["core"]["name"].str() ?: user["legacy"]["name"].str() ?: screenName

        var videos = MediaParser.videos(legacy["extended_entities"]["media"].arr())
        if (videos.isEmpty()) {
            val retweet = unwrap(legacy["retweeted_status_result"]["result"])
            val quoted = unwrap(t["quoted_status_result"]["result"])
            for (other in listOfNotNull(retweet, quoted)) {
                videos = MediaParser.videos(other["legacy"]["extended_entities"]["media"].arr())
                if (videos.isNotEmpty()) break
            }
        }
        return Post(
            id = id,
            authorName = name,
            screenName = screenName,
            text = cleanText(legacy["full_text"].str().orEmpty()),
            videos = videos,
        )
    }

    private fun unwrap(e: JsonElement?): JsonObject? {
        val o = e.obj() ?: return null
        return if (o["__typename"].str() == "TweetWithVisibilityResults") o["tweet"].obj() else o
    }
}
