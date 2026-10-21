package ru.madhare74.xvideo

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.madhare74.xvideo.link.LinkParser
import ru.madhare74.xvideo.link.ParsedLink

class LinkParserTest {

    private fun id(text: String) = (LinkParser.parse(text) as? ParsedLink.Post)?.id

    @Test
    fun standardLinks() {
        assertEquals("1849012345678901234", id("https://x.com/NASA/status/1849012345678901234"))
        assertEquals("1849012345678901234", id("https://twitter.com/NASA/status/1849012345678901234"))
        assertEquals("1849012345678901234", id("http://twitter.com/NASA/status/1849012345678901234"))
    }

    @Test
    fun hostVariants() {
        assertEquals("123", id("https://mobile.twitter.com/user/status/123"))
        assertEquals("123", id("https://www.x.com/user/status/123"))
        assertEquals("123", id("https://www.twitter.com/user/status/123"))
        assertEquals("123", id("https://m.twitter.com/user/status/123"))
        assertEquals("123", id("HTTPS://X.COM/User/status/123"))
    }

    @Test
    fun iStatusLinks() {
        assertEquals("1849012345678901234", id("https://x.com/i/status/1849012345678901234"))
        assertEquals("1849012345678901234", id("https://twitter.com/i/web/status/1849012345678901234"))
        assertEquals(
            ParsedLink.Post("55", "https://x.com/i/status/55"),
            LinkParser.parse("https://x.com/i/web/status/55"),
        )
    }

    @Test
    fun tailsAreIgnored() {
        assertEquals("1849", id("https://x.com/nasa/status/1849?s=20&t=AbCdEf123"))
        assertEquals("1849", id("https://x.com/nasa/status/1849/video/1"))
        assertEquals("1849", id("https://twitter.com/nasa/status/1849/photo/1"))
        assertEquals("1849", id("https://x.com/nasa/status/1849/"))
        assertEquals("1849", id("https://x.com/nasa/status/1849#reply"))
        assertEquals("1849", id("https://x.com/nasa/statuses/1849"))
    }

    @Test
    fun withoutSchemeAndInsideText() {
        assertEquals("777", id("x.com/user/status/777"))
        assertEquals("777", id("  twitter.com/user/status/777?s=46  "))
        assertEquals("777", id("Смотри, что нашёл: https://x.com/user/status/777?s=46 — огонь!"))
        assertEquals("777", id("(https://x.com/user/status/777)."))
    }

    @Test
    fun normalizedUrl() {
        val p = LinkParser.parse("https://mobile.twitter.com/NASA/status/42/video/1?s=20") as ParsedLink.Post
        assertEquals("https://x.com/NASA/status/42", p.url)
    }

    @Test
    fun shortLinks() {
        assertEquals(ParsedLink.Short("https://t.co/Ab12Cd34Ef"), LinkParser.parse("https://t.co/Ab12Cd34Ef"))
        assertEquals(ParsedLink.Short("https://t.co/Ab12Cd34Ef"), LinkParser.parse("t.co/Ab12Cd34Ef"))
        assertEquals(ParsedLink.Invalid, LinkParser.parse("https://t.co/"))
        assertEquals(ParsedLink.Invalid, LinkParser.parse("https://t.co/a/b"))
    }

    @Test
    fun invalidLinks() {
        listOf(
            "",
            "   ",
            "hello",
            "https://x.com/NASA",
            "https://x.com/NASA/status/",
            "https://x.com/NASA/status/abc",
            "https://x.com/NASA/status/12a3",
            "https://x.com/status/123",
            "https://x.com/NASA/status/000",
            "https://example.com/NASA/status/123",
            "https://notx.com/NASA/status/123",
            "https://x.com.evil.ru/NASA/status/123",
            "https://youtube.com/watch?v=123",
            "ftp://x.com/user/status/123",
            "x.com",
            "https://x.com/user/status/123456789012345678901234",
        ).forEach { assertEquals("«$it»", ParsedLink.Invalid, LinkParser.parse(it)) }
    }

    @Test
    fun findLink() {
        assertEquals("https://x.com/a/status/9", LinkParser.findLink("text https://twitter.com/a/status/9?s=1"))
        assertEquals(null, LinkParser.findLink("просто текст"))
    }
}
