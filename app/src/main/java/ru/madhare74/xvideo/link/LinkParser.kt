package ru.madhare74.xvideo.link

import java.net.URI

sealed interface ParsedLink {
    /** Ссылка на пост: найден числовой ID. [url] — ссылка в нормальном виде. */
    data class Post(val id: String, val url: String) : ParsedLink

    /** Короткая ссылка t.co — её нужно раскрыть переходом по редиректу. */
    data class Short(val url: String) : ParsedLink

    data object Invalid : ParsedLink
}

/**
 * Разбор ссылок на посты X без обращения к сети.
 *
 * Понимает x.com и twitter.com (с www., mobile., m. и без), пути `/<ник>/status/<id>`,
 * `/i/status/<id>`, `/i/web/status/<id>`, хвосты `?s=20&t=…`, `/video/1`, `/photo/1`,
 * ссылки без `https://`, а также короткие ссылки `t.co/…`. Ссылка может быть окружена
 * текстом — так приходит текст из «Поделиться».
 */
object LinkParser {

    private val postHosts = setOf("x.com", "twitter.com")
    private val hostPrefixes = listOf("www.", "mobile.", "m.")
    private val idPattern = Regex("""\d{1,20}""")
    private val tcoCode = Regex("""[A-Za-z0-9]{2,32}""")
    private val WRAPPERS = charArrayOf('<', '>', '"', '\'', '«', '»', '(', ')', '[', ']', '.', ',', ';', '!', '?', ':')

    fun parse(text: String): ParsedLink {
        for (token in text.split(Regex("""\s+"""))) {
            val result = parseToken(token)
            if (result != ParsedLink.Invalid) return result
        }
        return ParsedLink.Invalid
    }

    /** Нормализованная ссылка из текста, если она там есть (для подсказки из буфера обмена). */
    fun findLink(text: String): String? = when (val p = parse(text)) {
        is ParsedLink.Post -> p.url
        is ParsedLink.Short -> p.url
        ParsedLink.Invalid -> null
    }

    private fun parseToken(raw: String): ParsedLink {
        var token = raw.trim().trim(*WRAPPERS)
        if (token.isEmpty()) return ParsedLink.Invalid
        if (!token.contains("://")) {
            if (!token.contains('/')) return ParsedLink.Invalid
            token = "https://$token"
        }
        val uri = try {
            URI(token)
        } catch (e: Exception) {
            return ParsedLink.Invalid
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return ParsedLink.Invalid
        var host = uri.host?.lowercase() ?: return ParsedLink.Invalid
        hostPrefixes.firstOrNull { host.startsWith(it) }?.let { host = host.removePrefix(it) }
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }

        if (host == "t.co") {
            val code = segments.singleOrNull() ?: return ParsedLink.Invalid
            if (!tcoCode.matches(code)) return ParsedLink.Invalid
            return ParsedLink.Short("https://t.co/$code")
        }
        if (host !in postHosts) return ParsedLink.Invalid

        val statusAt = segments.indexOfFirst { it.equals("status", true) || it.equals("statuses", true) }
        if (statusAt < 1 || statusAt + 1 >= segments.size) return ParsedLink.Invalid
        val id = segments[statusAt + 1]
        if (!idPattern.matches(id) || id.all { it == '0' }) return ParsedLink.Invalid
        val owner = segments[statusAt - 1]
        val user = if (statusAt >= 2 && segments[0].equals("i", true)) "i" else owner
        val normalized = if (user.equals("i", true)) "https://x.com/i/status/$id" else "https://x.com/$user/status/$id"
        return ParsedLink.Post(id = id, url = normalized)
    }
}
