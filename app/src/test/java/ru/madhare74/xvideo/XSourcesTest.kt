package ru.madhare74.xvideo

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.madhare74.xvideo.data.FetchOutcome
import ru.madhare74.xvideo.data.GuestApiSource
import ru.madhare74.xvideo.data.SizeProbe
import ru.madhare74.xvideo.data.SyndicationSource
import ru.madhare74.xvideo.data.SyndicationToken
import ru.madhare74.xvideo.data.TcoResolver
import ru.madhare74.xvideo.data.XUnavailableException

class XSourcesTest {

    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient()
    private val base = server.url("/").toString()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun syndicationRequestAndParse() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("synd_single_video.json")))
        val outcome = SyndicationSource(client, base).fetch("1849012345678901234")
        assertTrue(outcome is FetchOutcome.Found)
        val request = server.takeRequest()
        assertEquals("/tweet-result", request.requestUrl!!.encodedPath)
        assertEquals("1849012345678901234", request.requestUrl!!.queryParameter("id"))
        assertEquals(SyndicationToken.forId("1849012345678901234"), request.requestUrl!!.queryParameter("token"))
    }

    @Test
    fun syndicationHttpCodes() = runTest {
        val source = SyndicationSource(client, base)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(FetchOutcome.NotFound, source.fetch("1"))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(FetchOutcome.BadFormat, source.fetch("1"))
        server.enqueue(MockResponse().setResponseCode(503))
        val error = runCatching { source.fetch("1") }.exceptionOrNull()
        assertTrue(error is XUnavailableException)
    }

    @Test
    fun guestApiActivatesTokenAndRetriesOnce() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("guest_token.json")))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody("""{"guest_token":"222"}"""))
        server.enqueue(MockResponse().setBody(Fixtures.text("gql_video.json")))
        val outcome = GuestApiSource(client, base).fetch("1849012345678901234")
        assertTrue(outcome is FetchOutcome.Found)

        val activate = server.takeRequest()
        assertEquals("POST", activate.method)
        assertEquals("/1.1/guest/activate.json", activate.path)
        assertTrue(activate.getHeader("Authorization")!!.startsWith("Bearer "))
        val first = server.takeRequest()
        assertTrue(first.path!!.contains("/TweetResultByRestId"))
        assertEquals("1849999999999999999", first.getHeader("x-guest-token"))
        assertTrue(first.requestUrl!!.queryParameter("variables")!!.contains("\"tweetId\":\"1849012345678901234\""))
        server.takeRequest()
        assertEquals("222", server.takeRequest().getHeader("x-guest-token"))
    }

    @Test
    fun guestApiNoTokenIsFormatChange() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(FetchOutcome.BadFormat, GuestApiSource(client, base).fetch("1"))
    }

    @Test
    fun tcoRedirect() = runTest {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "https://x.com/NASA/status/42"))
        assertEquals("https://x.com/NASA/status/42", TcoResolver(client).resolve(server.url("/abc").toString()))
    }

    @Test
    fun tcoHtmlPage() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """<head><meta name="referrer" content="always"><noscript><META http-equiv="refresh" content="0;URL=https://twitter.com/NASA/status/42/video/1"></noscript><title>https://twitter.com/NASA/status/42/video/1</title></head>""",
            ),
        )
        assertEquals("https://twitter.com/NASA/status/42/video/1", TcoResolver(client).resolve(server.url("/abc").toString()))
    }

    @Test
    fun sizeFromHead() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Length", "12897485"))
        assertEquals(12_897_485L, SizeProbe(client).size(server.url("/v.mp4").toString()))
        assertEquals("HEAD", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(null, SizeProbe(client).size(server.url("/v.mp4").toString()))
    }

    @Test
    fun tokenHasNoDotsOrZeros() {
        val token = SyndicationToken.forId("1849012345678901234")
        assertTrue(token.isNotEmpty())
        assertTrue(token.none { it == '.' || it == '0' })
        assertTrue(token.all { it.isLetterOrDigit() })
    }
}
