package ru.madhare74.xvideo

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.madhare74.xvideo.data.AuthApiSource
import ru.madhare74.xvideo.data.FetchOutcome
import ru.madhare74.xvideo.data.InMemorySessionStore
import ru.madhare74.xvideo.data.LookupResult
import ru.madhare74.xvideo.data.PostRepository
import ru.madhare74.xvideo.data.PostSource
import ru.madhare74.xvideo.data.SessionExpiredException
import ru.madhare74.xvideo.data.ShortLinkResolver
import ru.madhare74.xvideo.data.SyndicationParser
import ru.madhare74.xvideo.data.XCookies
import ru.madhare74.xvideo.data.XSession
import ru.madhare74.xvideo.domain.AppError
import java.io.IOException

class AuthTest {

    private val token = "0123456789abcdef0123456789abcdef01234567"
    private val ct0 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"

    // --- Cookies ---

    @Test
    fun parseCookieHeader() {
        val c = XCookies.parse("guest_id=v1%3A1; auth_token=$token; ct0=$ct0; lang=ru")!!
        assertEquals(token, c.authToken)
        assertEquals(ct0, c.ct0)
        assertEquals("auth_token=$token; ct0=$ct0", c.header())
        assertNull(XCookies.parse("guest_id=1; ct0=$ct0"))
        assertNull(XCookies.parse(null))
        assertNull(XCookies.parse("auth_token=not-a-token"))
    }

    @Test
    fun manualInput() {
        assertEquals(XCookies(token, null), XCookies.fromManualInput("  $token  "))
        assertEquals(XCookies(token, null), XCookies.fromManualInput("\"$token\""))
        assertEquals(XCookies(token, null), XCookies.fromManualInput("auth_token=$token"))
        assertEquals(XCookies(token, ct0), XCookies.fromManualInput("auth_token=$token; ct0=$ct0"))
        assertNull(XCookies.fromManualInput(""))
        assertNull(XCookies.fromManualInput("мой пароль"))
        assertNull(XCookies.fromManualInput("12345"))
    }

    @Test
    fun tokenIsNotPrinted() {
        assertFalse(XCookies(token, ct0).toString().contains(token))
    }

    @Test
    fun sessionLoginLogout() {
        val store = InMemorySessionStore()
        var cleared = false
        val session = XSession(store).apply { onLogout = { cleared = true } }
        assertFalse(session.isLoggedIn)
        session.login(XCookies(token, null))
        assertTrue(session.isLoggedIn)
        session.updateCt0(ct0)
        assertEquals(ct0, store.load()?.ct0)
        assertTrue("сессия переживает перезапуск", XSession(store).isLoggedIn)
        session.logout()
        assertFalse(session.isLoggedIn)
        assertNull(store.load())
        assertTrue(cleared)
    }

    // --- Запрос от имени аккаунта ---

    private val server = MockWebServer().apply { start() }
    private val base = server.url("/i/api").toString()

    @After
    fun tearDown() = server.shutdown()

    private fun loggedIn(ct0: String? = this.ct0) = XSession(InMemorySessionStore(XCookies(token, ct0)))

    @Test
    fun sendsSessionHeaders() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("gql_video.json")))
        val outcome = AuthApiSource(OkHttpClient(), loggedIn(), base).fetch("1849012345678901234")
        assertTrue(outcome is FetchOutcome.Found)
        val r = server.takeRequest()
        assertTrue(r.path!!.startsWith("/i/api/graphql/"))
        assertTrue(r.path!!.contains("/TweetResultByRestId"))
        assertEquals("OAuth2Session", r.getHeader("X-Twitter-Auth-Type"))
        assertEquals(ct0, r.getHeader("x-csrf-token"))
        assertEquals("auth_token=$token; ct0=$ct0", r.getHeader("Cookie"))
        assertTrue(r.getHeader("Authorization")!!.startsWith("Bearer "))
        assertNull(r.getHeader("x-guest-token"))
    }

    @Test
    fun getsCt0FromXAndRetries() = runTest {
        val session = loggedIn(ct0 = null)
        server.enqueue(MockResponse().setResponseCode(403).addHeader("Set-Cookie", "ct0=$ct0; Path=/; Domain=.x.com; Secure"))
        server.enqueue(MockResponse().setBody(Fixtures.text("gql_video.json")))
        val outcome = AuthApiSource(OkHttpClient(), session, base).fetch("1849012345678901234")
        assertTrue(outcome is FetchOutcome.Found)
        assertNull(server.takeRequest().getHeader("x-csrf-token"))
        assertEquals(ct0, server.takeRequest().getHeader("x-csrf-token"))
        assertEquals(ct0, session.cookies.value?.ct0)
    }

    @Test
    fun expiredSessionLogsOut() = runTest {
        val session = loggedIn()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"errors":[{"code":32,"message":"Could not authenticate you."}]}"""))
        val e = runCatching { AuthApiSource(OkHttpClient(), session, base).fetch("1") }.exceptionOrNull()
        assertTrue(e is SessionExpiredException)
        assertFalse(session.isLoggedIn)
    }

    @Test
    fun restrictedEvenForAccount() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.text("gql_protected.json")))
        assertEquals(FetchOutcome.Restricted, AuthApiSource(OkHttpClient(), loggedIn(), base).fetch("1"))
    }

    // --- Порядок источников ---

    private val found = SyndicationParser.parse(Fixtures.text("synd_single_video.json"))

    private class Counting(private val answer: suspend () -> FetchOutcome) : PostSource {
        var calls = 0
        override suspend fun fetch(id: String): FetchOutcome {
            calls++
            return answer()
        }
    }

    private suspend fun lookup(guest: PostSource, auth: PostSource, loggedIn: Boolean) =
        PostRepository(guest, null, ShortLinkResolver { null }, auth, { loggedIn })
            .lookup("https://x.com/NASA/status/1849012345678901234")

    private fun LookupResult.error() = (this as LookupResult.Failure).error

    @Test
    fun accountUsedOnlyWhenGuestRestricted() = runTest {
        val auth = Counting { found }
        assertTrue(lookup(Counting { found }, auth, loggedIn = true) is LookupResult.Success)
        assertEquals("гостевой доступ сработал — аккаунт не нужен", 0, auth.calls)

        assertTrue(lookup(Counting { FetchOutcome.Restricted }, auth, loggedIn = true) is LookupResult.Success)
        assertEquals(1, auth.calls)
    }

    @Test
    fun notLoggedInKeepsRestricted() = runTest {
        val auth = Counting { found }
        assertEquals(AppError.Restricted, lookup(Counting { FetchOutcome.Restricted }, auth, loggedIn = false).error())
        assertEquals(0, auth.calls)
    }

    @Test
    fun accountAlsoRestricted() = runTest {
        assertEquals(
            AppError.RestrictedForAccount,
            lookup(Counting { FetchOutcome.Restricted }, Counting { FetchOutcome.Restricted }, loggedIn = true).error(),
        )
    }

    @Test
    fun accountSessionExpired() = runTest {
        assertEquals(
            AppError.SessionExpired,
            lookup(Counting { FetchOutcome.Restricted }, Counting { throw SessionExpiredException() }, loggedIn = true).error(),
        )
    }

    @Test
    fun accountOffline() = runTest {
        assertEquals(
            AppError.Unavailable,
            lookup(Counting { FetchOutcome.Restricted }, Counting { throw IOException() }, loggedIn = true).error(),
        )
    }

    @Test
    fun accountHelpsWhenGuestBroken() = runTest {
        assertTrue(lookup(Counting { FetchOutcome.BadFormat }, Counting { found }, loggedIn = true) is LookupResult.Success)
    }

    @Test
    fun noVideoNotRetriedWithAccount() = runTest {
        val auth = Counting { found }
        val noVideo = SyndicationParser.parse(Fixtures.text("synd_no_video.json"))
        assertEquals(AppError.NoVideo, lookup(Counting { noVideo }, auth, loggedIn = true).error())
        assertEquals(0, auth.calls)
    }
}
