package ru.madhare74.xvideo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.madhare74.xvideo.data.FetchOutcome
import ru.madhare74.xvideo.data.LookupResult
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.PostSource
import ru.madhare74.xvideo.data.ShortLinkResolver
import ru.madhare74.xvideo.data.SyndicationParser
import ru.madhare74.xvideo.domain.AppError
import java.io.IOException

class PostRepositoryTest {

    private val found = SyndicationParser.parse(Fixtures.text("synd_single_video.json"))
    private val noVideo = SyndicationParser.parse(Fixtures.text("synd_no_video.json"))

    private class Counting(private val answer: () -> FetchOutcome) : PostSource {
        var calls = 0
        val ids = mutableListOf<String>()
        override suspend fun fetch(id: String): FetchOutcome {
            calls++
            ids += id
            return answer()
        }
    }

    private val offline = PostSource { throw IOException("нет сети") }
    private val noShort = ShortLinkResolver { null }

    private suspend fun lookup(
        primary: PostSource,
        fallback: PostSource? = null,
        input: String = "https://x.com/NASA/status/1849012345678901234",
        short: ShortLinkResolver = noShort,
    ) = PostRepository(primary, fallback, short).lookup(input)

    private fun LookupResult.error() = (this as LookupResult.Failure).error

    @Test
    fun invalidLinkDoesNotTouchNetwork() = runTest {
        val src = Counting { found }
        assertEquals(AppError.InvalidLink, lookup(src, src, input = "привет").error())
        assertEquals(0, src.calls)
    }

    @Test
    fun primarySuccessSkipsFallback() = runTest {
        val fallback = Counting { FetchOutcome.BadFormat }
        val result = lookup(Counting { found }, fallback)
        assertTrue(result is LookupResult.Success)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun fallbackUsedWhenPrimaryFormatChanged() = runTest {
        val result = lookup(Counting { FetchOutcome.BadFormat }, Counting { found })
        assertTrue(result is LookupResult.Success)
    }

    @Test
    fun fallbackUsedWhenPrimaryOffline() = runTest {
        assertTrue(lookup(offline, Counting { found }) is LookupResult.Success)
    }

    @Test
    fun bothFormatsChanged() = runTest {
        assertEquals(AppError.FormatChanged, lookup(Counting { FetchOutcome.BadFormat }, Counting { FetchOutcome.BadFormat }).error())
    }

    @Test
    fun parserCrashIsFormatChanged() = runTest {
        assertEquals(AppError.FormatChanged, lookup(PostSource { error("неожиданно") }).error())
    }

    @Test
    fun offlineEverywhere() = runTest {
        assertEquals(AppError.Unavailable, lookup(offline, offline).error())
    }

    @Test
    fun restrictedWins() = runTest {
        assertEquals(AppError.Restricted, lookup(Counting { FetchOutcome.NotFound }, Counting { FetchOutcome.Restricted }).error())
    }

    @Test
    fun notFound() = runTest {
        assertEquals(AppError.NotFound, lookup(Counting { FetchOutcome.NotFound }, Counting { FetchOutcome.BadFormat }).error())
        assertEquals(AppError.NotFound, lookup(Counting { FetchOutcome.NotFound }, offline).error())
    }

    @Test
    fun postWithoutVideo() = runTest {
        assertEquals(AppError.NoVideo, lookup(Counting { noVideo }).error())
    }

    @Test
    fun shortLinkResolved() = runTest {
        val src = Counting { found }
        val result = lookup(
            src,
            input = "https://t.co/Ab12Cd34Ef",
            short = ShortLinkResolver { "https://x.com/NASA/status/1849012345678901234/video/1" },
        )
        assertTrue(result is LookupResult.Success)
        assertEquals(listOf("1849012345678901234"), src.ids)
    }

    @Test
    fun shortLinkToOtherSite() = runTest {
        val src = Counting { found }
        val result = lookup(src, input = "https://t.co/Ab12Cd34Ef", short = ShortLinkResolver { "https://example.com/" })
        assertEquals(AppError.InvalidLink, result.error())
        assertEquals(0, src.calls)
    }

    @Test
    fun shortLinkOffline() = runTest {
        val result = lookup(Counting { found }, input = "t.co/Ab12Cd34Ef", short = ShortLinkResolver { throw IOException() })
        assertEquals(AppError.Unavailable, result.error())
    }
}
