package ru.madhare74.xvideo.data

import kotlinx.coroutines.CancellationException
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.Post
import ru.madhare74.xvideo.link.LinkParser
import ru.madhare74.xvideo.link.ParsedLink
import java.io.IOException

sealed interface LookupResult {
    data class Success(val post: Post) : LookupResult
    data class Failure(val error: AppError) : LookupResult
}

/**
 * Поиск видео по ссылке: разбор ссылки (без сети), раскрытие t.co, запрос к основному
 * источнику и, если он не дал результата, к запасному. Если гостю X пост не отдаёт
 * («нужен вход»), а вход выполнен — ещё один запрос от имени аккаунта ([authenticated]).
 */
class PostRepository(
    private val primary: PostSource,
    private val fallback: PostSource?,
    private val shortLinks: ShortLinkResolver,
    private val authenticated: PostSource? = null,
    private val isLoggedIn: () -> Boolean = { false },
) {

    suspend fun lookup(input: String): LookupResult {
        val id = when (val link = LinkParser.parse(input)) {
            ParsedLink.Invalid -> return LookupResult.Failure(AppError.InvalidLink)
            is ParsedLink.Post -> link.id
            is ParsedLink.Short -> {
                val target = try {
                    shortLinks.resolve(link.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return LookupResult.Failure(AppError.Unavailable)
                }
                val resolved = target?.let { LinkParser.parse(it) }
                (resolved as? ParsedLink.Post)?.id ?: return LookupResult.Failure(AppError.InvalidLink)
            }
        }
        return lookupId(id)
    }

    suspend fun lookupId(id: String): LookupResult {
        val first = attempt(primary, id)
        val outcomes = mutableListOf(first)
        if (first !is Attempt.Done || first.outcome !is FetchOutcome.Found) {
            fallback?.let { outcomes += attempt(it, id) }
        }
        val guest = combine(outcomes)
        val auth = authenticated
        // С аккаунтом пробуем и тогда, когда гостевой доступ сломался: у вошедшего он отдельный.
        if (guest is LookupResult.Failure && guest.error in RETRY_WITH_ACCOUNT && auth != null && isLoggedIn()) {
            return lookupWithAccount(auth, id)
        }
        return guest
    }

    private suspend fun lookupWithAccount(source: PostSource, id: String): LookupResult {
        val outcome = try {
            source.fetch(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            return LookupResult.Failure(AppError.SessionExpired)
        } catch (e: IOException) {
            return LookupResult.Failure(AppError.Unavailable)
        } catch (e: Exception) {
            FetchOutcome.BadFormat
        }
        return when (outcome) {
            is FetchOutcome.Found -> combine(listOf(Attempt.Done(outcome)))
            FetchOutcome.Restricted -> LookupResult.Failure(AppError.RestrictedForAccount)
            FetchOutcome.NotFound -> LookupResult.Failure(AppError.NotFound)
            FetchOutcome.BadFormat -> LookupResult.Failure(AppError.FormatChanged)
        }
    }

    private sealed interface Attempt {
        data class Done(val outcome: FetchOutcome) : Attempt
        data object NetworkError : Attempt
    }

    private suspend fun attempt(source: PostSource, id: String): Attempt =
        try {
            Attempt.Done(source.fetch(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Attempt.NetworkError
        } catch (e: Exception) {
            // Любой неожиданный сбой разбора — не падаем, а считаем, что формат изменился.
            Attempt.Done(FetchOutcome.BadFormat)
        }

    /** Лучший из ответов источников: найден > закрыт > не найден > формат изменился > сеть. */
    private fun combine(attempts: List<Attempt>): LookupResult {
        val outcomes = attempts.filterIsInstance<Attempt.Done>().map { it.outcome }
        outcomes.firstOrNull { it is FetchOutcome.Found }?.let { found ->
            val post = (found as FetchOutcome.Found).post
            return if (post.videos.isEmpty() || post.videos.all { it.variants.isEmpty() }) {
                LookupResult.Failure(AppError.NoVideo)
            } else {
                LookupResult.Success(post.copy(videos = post.videos.filter { it.variants.isNotEmpty() }))
            }
        }
        val error = when {
            FetchOutcome.Restricted in outcomes -> AppError.Restricted
            FetchOutcome.NotFound in outcomes -> AppError.NotFound
            FetchOutcome.BadFormat in outcomes -> AppError.FormatChanged
            else -> AppError.Unavailable
        }
        return LookupResult.Failure(error)
    }

    private companion object {
        val RETRY_WITH_ACCOUNT = setOf(AppError.Restricted, AppError.FormatChanged, AppError.Unavailable)
    }
}
