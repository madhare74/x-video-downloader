package ru.madhare74.xvideo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Получение данных о постах X. Это единственное место, которое знает, как устроены
 * неофициальные публичные точки доступа X: если X их изменит, чинить нужно только здесь
 * и в разборе ответов (XParsers.kt).
 */
object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            chain.proceed(
                if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", USER_AGENT).build() else req,
            )
        })
        .build()
}

/** Источник информации о посте. Сетевые сбои — [IOException]. */
fun interface PostSource {
    suspend fun fetch(id: String): FetchOutcome
}

/** HTTP-ответ X, по которому видно, что сервис перегружен или недоступен. */
class XUnavailableException(message: String) : IOException(message)

internal suspend fun OkHttpClient.call(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
    newCall(request).execute().use { r: Response -> r.code to (r.body?.string() ?: "") }
}

/**
 * Токен сервиса встраивания — тот же, что вычисляет скрипт встраивания постов X:
 * `((id / 1e15) * π).toString(36)` без точки и нулей.
 */
object SyndicationToken {
    fun forId(id: String): String {
        val value = id.toDouble() / 1e15 * Math.PI
        return JsNumber.toString36(value).replace(Regex("(0+|\\.)"), "")
    }
}

/** Повторение `Number.prototype.toString(36)` из JavaScript (алгоритм V8) для положительных чисел. */
internal object JsNumber {
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun toString36(value: Double): String {
        val radix = 36
        var integer = Math.floor(value)
        var fraction = value - integer
        var delta = 0.5 * (Math.nextUp(value) - value)
        delta = maxOf(Math.nextUp(0.0), delta)
        val frac = StringBuilder()
        if (fraction >= delta) {
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                frac.append(DIGITS[digit])
                fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // Округление вверх с переносом.
                        var i = frac.length - 1
                        while (true) {
                            if (i < 0) {
                                integer += 1
                                frac.setLength(0)
                                break
                            }
                            val d = DIGITS.indexOf(frac[i])
                            if (d + 1 < radix) {
                                frac.setCharAt(i, DIGITS[d + 1])
                                frac.setLength(i + 1)
                                break
                            }
                            i--
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        val intPart = StringBuilder()
        var n = integer
        if (n < 1) intPart.append('0')
        while (n >= 1) {
            val d = (n % radix).toInt()
            intPart.append(DIGITS[d])
            n = Math.floor(n / radix)
        }
        intPart.reverse()
        return if (frac.isEmpty()) intPart.toString() else "$intPart.$frac"
    }
}

/** Основной способ: публичный сервис встраивания постов X. */
class SyndicationSource(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://cdn.syndication.twimg.com",
) : PostSource {

    override suspend fun fetch(id: String): FetchOutcome {
        val url = "${baseUrl.trimEnd('/')}/tweet-result".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("token", SyndicationToken.forId(id))
            .addQueryParameter("lang", "en")
            .build()
        val (code, body) = client.call(Request.Builder().url(url).header("Accept", "application/json").build())
        return when {
            code == 404 -> FetchOutcome.NotFound
            code == 429 || code >= 500 -> throw XUnavailableException("syndication HTTP $code")
            code != 200 -> FetchOutcome.BadFormat
            else -> SyndicationParser.parse(body)
        }
    }
}

/** Запасной способ: гостевой доступ, которым пользуется сайт X без входа в аккаунт. */
class GuestApiSource(
    private val client: OkHttpClient,
    private val apiBase: String = "https://api.x.com",
) : PostSource {

    @Volatile private var guestToken: String? = null

    override suspend fun fetch(id: String): FetchOutcome {
        var token = guestToken ?: activate() ?: return FetchOutcome.BadFormat
        var (code, body) = client.call(tweetRequest(id, token))
        if (code == 401 || code == 403 || code == 429) {
            // Гостевой токен устарел — берём новый и повторяем один раз.
            token = activate() ?: return FetchOutcome.BadFormat
            val retry = client.call(tweetRequest(id, token))
            code = retry.first
            body = retry.second
        }
        return when {
            code == 404 -> FetchOutcome.NotFound
            code == 429 || code >= 500 -> throw XUnavailableException("guest HTTP $code")
            code != 200 -> FetchOutcome.BadFormat
            else -> GuestApiParser.parse(body)
        }
    }

    private suspend fun activate(): String? {
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/1.1/guest/activate.json")
            .header("Authorization", "Bearer $BEARER")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val (code, body) = client.call(request)
        if (code == 429 || code >= 500) throw XUnavailableException("guest activate HTTP $code")
        if (code != 200) return null
        val token = parseRoot(body)["guest_token"].str()
        guestToken = token
        return token
    }

    private fun tweetRequest(id: String, token: String): Request {
        val variables = buildJsonObject {
            put("tweetId", id)
            put("withCommunity", false)
            put("includePromotedContent", false)
            put("withVoice", false)
        }
        val features = buildJsonObject { FEATURES.forEach { (k, v) -> put(k, v) } }
        val toggles = buildJsonObject {
            put("withArticleRichContentState", true)
            put("withArticlePlainText", false)
            put("withGrokAnalyze", false)
        }
        val url = "${apiBase.trimEnd('/')}/graphql/$QUERY_ID/TweetResultByRestId".toHttpUrl().newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("features", features.toString())
            .addQueryParameter("fieldToggles", toggles.toString())
            .build()
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $BEARER")
            .header("x-guest-token", token)
            .header("x-twitter-active-user", "yes")
            .header("x-twitter-client-language", "en")
            .header("Content-Type", "application/json")
            .header("Cookie", "guest_id=v1%3A$token")
            .build()
    }

    companion object {
        /** Публичный ключ веб-клиента X (тот же, что в коде сайта x.com). */
        const val BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val QUERY_ID = "I9GDzyCGZL2wSoYFFrrTVw"

        val FEATURES: Map<String, Boolean> = linkedMapOf(
            "creator_subscriptions_tweet_preview_api_enabled" to true,
            "premium_content_api_read_enabled" to false,
            "communities_web_enable_tweet_community_results_fetch" to true,
            "c9s_tweet_anatomy_moderator_badge_enabled" to true,
            "responsive_web_grok_analyze_button_fetch_trends_enabled" to false,
            "responsive_web_grok_analyze_post_followups_enabled" to false,
            "responsive_web_jetfuel_frame" to false,
            "responsive_web_grok_share_attachment_enabled" to true,
            "articles_preview_enabled" to true,
            "responsive_web_edit_tweet_api_enabled" to true,
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
            "view_counts_everywhere_api_enabled" to true,
            "longform_notetweets_consumption_enabled" to true,
            "responsive_web_twitter_article_tweet_consumption_enabled" to true,
            "tweet_awards_web_tipping_enabled" to false,
            "creator_subscriptions_quote_tweet_preview_enabled" to false,
            "freedom_of_speech_not_reach_fetch_enabled" to true,
            "standardized_nudges_misinfo" to true,
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
            "rweb_video_screen_enabled" to false,
            "profile_label_improvements_pcf_label_in_post_enabled" to true,
            "rweb_tipjar_consumption_enabled" to true,
            "verified_phone_label_enabled" to false,
            "longform_notetweets_rich_text_read_enabled" to true,
            "longform_notetweets_inline_media_enabled" to true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
            "responsive_web_graphql_timeline_navigation_enabled" to true,
            "responsive_web_graphql_exclude_directive_enabled" to true,
            "responsive_web_enhance_cards_enabled" to false,
        )
    }
}

/** Раскрытие коротких ссылок t.co переходом по редиректу. */
fun interface ShortLinkResolver {
    /** Возвращает адрес, на который ведёт ссылка, или null, если его не удалось определить. */
    suspend fun resolve(url: String): String?
}

class TcoResolver(client: OkHttpClient) : ShortLinkResolver {

    private val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val xLink = Regex("""https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/[^\s"'<>]+""")

    override suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        noRedirects.newCall(Request.Builder().url(url).build()).execute().use { r ->
            when {
                r.isRedirect -> r.header("Location")
                r.code == 200 -> {
                    // Браузерам t.co иногда отдаёт страницу с переходом вместо редиректа.
                    val body = r.body?.string().orEmpty()
                    xLink.find(body)?.value?.replace("&amp;", "&")
                }
                r.code == 429 || r.code >= 500 -> throw XUnavailableException("t.co HTTP ${r.code}")
                else -> null
            }
        }
    }
}

/** Размеры файлов по заголовку Content-Length (запрос HEAD). Если сервер его не сообщает — null. */
class SizeProbe(private val client: OkHttpClient) {
    suspend fun size(url: String): Long? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } else null
            }
        } catch (e: IOException) {
            null
        }
    }
}
