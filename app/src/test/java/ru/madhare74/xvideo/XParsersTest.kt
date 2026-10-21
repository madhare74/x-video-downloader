package ru.madhare74.xvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.madhare74.xvideo.data.FetchOutcome
import ru.madhare74.xvideo.data.GuestApiParser
import ru.madhare74.xvideo.data.SyndicationParser
import ru.madhare74.xvideo.domain.Post

class XParsersTest {

    private fun synd(name: String) = SyndicationParser.parse(Fixtures.text(name))
    private fun gql(name: String) = GuestApiParser.parse(Fixtures.text(name))
    private fun FetchOutcome.post(): Post = (this as FetchOutcome.Found).post

    @Test
    fun singleVideo() {
        val post = synd("synd_single_video.json").post()
        assertEquals("1849012345678901234", post.id)
        assertEquals("NASA", post.authorName)
        assertEquals("NASA", post.screenName)
        assertTrue(post.text.startsWith("Watch the launch"))
        assertFalse("ссылка на вложение убрана из текста", post.text.contains("t.co"))
        assertEquals(1, post.videos.size)
        val video = post.videos[0]
        assertEquals(1, video.index)
        assertFalse(video.isGif)
        assertEquals(45_120L, video.durationMs)
        assertEquals("https://pbs.twimg.com/ext_tw_video_thumb/1849012200000000000/pu/img/thumb01.jpg", video.thumbnailUrl)
        assertEquals(listOf(1080, 720, 360, 270), video.variants.map { it.height })
        assertEquals(
            "https://video.twimg.com/ext_tw_video/1849012200000000000/pu/vid/avc1/1920x1080/fhdQ.mp4?tag=12",
            video.best?.url,
        )
    }

    @Test
    fun multipleVideosSkipPhotos() {
        val post = synd("synd_multi_video.json").post()
        assertEquals("Тестовый Канал", post.authorName)
        assertEquals("test_channel", post.screenName)
        assertEquals(2, post.videos.size)
        assertEquals(listOf(1, 2), post.videos.map { it.index })
        assertEquals(listOf(1280, 852, 568), post.videos[0].variants.map { it.height })
        assertEquals(listOf(720, 270), post.videos[1].variants.map { it.height })
        assertTrue(post.videos[1].variants[0].url.contains("avc1"))
        assertEquals(3_723_000L, post.videos[1].durationMs)
    }

    @Test
    fun gif() {
        val post = synd("synd_gif.json").post()
        val video = post.videos.single()
        assertTrue(video.isGif)
        assertEquals(null, video.durationMs)
        assertEquals(1, video.variants.size)
        assertEquals(480, video.variants[0].width)
        assertEquals(270, video.variants[0].height)
    }

    @Test
    fun noVideo() {
        assertTrue(synd("synd_no_video.json").post().videos.isEmpty())
        assertTrue(synd("synd_text_only.json").post().videos.isEmpty())
    }

    @Test
    fun videoFromQuotedPost() {
        val post = synd("synd_quoted_video.json").post()
        assertEquals("quoter", post.screenName)
        assertEquals(1, post.videos.size)
    }

    @Test
    fun tombstones() {
        assertEquals(FetchOutcome.Restricted, synd("synd_tombstone_protected.json"))
        assertEquals(FetchOutcome.Restricted, synd("synd_tombstone_age.json"))
        assertEquals(FetchOutcome.NotFound, synd("synd_tombstone_deleted.json"))
    }

    @Test
    fun emptyMeansNotFound() {
        assertEquals(FetchOutcome.NotFound, SyndicationParser.parse(""))
        assertEquals(FetchOutcome.NotFound, SyndicationParser.parse("{}"))
    }

    @Test
    fun unknownFormat() {
        assertEquals(FetchOutcome.BadFormat, synd("synd_unknown_format.json"))
        assertEquals(FetchOutcome.BadFormat, synd("synd_broken.json"))
        assertEquals(FetchOutcome.BadFormat, SyndicationParser.parse("""{"__typename":"Tweet","id_str":"1"}"""))
        assertEquals(
            FetchOutcome.BadFormat,
            SyndicationParser.parse("""{"__typename":"Tweet","id_str":"1","user":{"screen_name":"a"},"mediaDetails":[{"type":"video"}]}"""),
        )
        assertEquals(FetchOutcome.BadFormat, SyndicationParser.parse("[1,2,3]"))
    }

    @Test
    fun guestApiVideo() {
        val post = gql("gql_video.json").post()
        assertEquals("1849012345678901234", post.id)
        assertEquals("NASA", post.screenName)
        assertEquals("Watch the launch of the Artemis rocket.", post.text)
        assertEquals(listOf(720, 360), post.videos.single().variants.map { it.height })
    }

    @Test
    fun guestApiOldUserObject() {
        val post = gql("gql_visibility_video.json").post()
        assertEquals("old_style", post.screenName)
        assertEquals("Old Style", post.authorName)
        assertEquals(1, post.videos.size)
    }

    @Test
    fun guestApiErrors() {
        assertEquals(FetchOutcome.Restricted, gql("gql_protected.json"))
        assertEquals(FetchOutcome.Restricted, gql("gql_age.json"))
        assertEquals(FetchOutcome.NotFound, gql("gql_not_found.json"))
        assertEquals(FetchOutcome.BadFormat, gql("gql_unknown.json"))
        assertEquals(FetchOutcome.BadFormat, GuestApiParser.parse("""{"errors":[{"message":"Bad request"}]}"""))
        assertEquals(FetchOutcome.BadFormat, GuestApiParser.parse("not json"))
    }
}
