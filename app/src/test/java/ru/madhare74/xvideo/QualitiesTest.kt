package ru.madhare74.xvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.madhare74.xvideo.domain.Qualities
import ru.madhare74.xvideo.domain.RawVariant

class QualitiesTest {

    @Test
    fun onlyMp4SortedByResolution() {
        val result = Qualities.select(
            listOf(
                RawVariant("https://video.twimg.com/a/pl/x.m3u8", "application/x-mpegURL", null),
                RawVariant("https://video.twimg.com/a/vid/avc1/640x360/b.mp4?tag=12", "video/mp4", 832_000),
                RawVariant("https://video.twimg.com/a/vid/avc1/1920x1080/c.mp4?tag=12", "video/mp4", 10_368_000),
                RawVariant("https://video.twimg.com/a/vid/avc1/480x270/d.mp4?tag=12", "video/mp4", 256_000),
                RawVariant("https://video.twimg.com/a/vid/avc1/1280x720/e.mp4?tag=12", "video/mp4", 2_176_000),
            ),
        )
        assertEquals(listOf(1080, 720, 360, 270), result.map { it.height })
        assertEquals(listOf("1920×1080", "1280×720", "640×360", "480×270"), result.map { it.resolution })
        assertTrue(result.none { it.url.contains("m3u8") })
    }

    @Test
    fun sameResolutionKeepsHighestBitrate() {
        val result = Qualities.select(
            listOf(
                RawVariant("https://v/vid/hevc/0/0/1280x720/h.mp4", "video/mp4", 1_500_000),
                RawVariant("https://v/vid/avc1/0/0/1280x720/a.mp4", "video/mp4", 2_176_000),
            ),
        )
        assertEquals(1, result.size)
        assertEquals("https://v/vid/avc1/0/0/1280x720/a.mp4", result[0].url)
    }

    @Test
    fun verticalVideoSortedByArea() {
        val result = Qualities.select(
            listOf(
                RawVariant("https://v/vid/avc1/320x568/a.mp4", "video/mp4", 632_000),
                RawVariant("https://v/vid/avc1/720x1280/b.mp4", "video/mp4", 2_176_000),
                RawVariant("https://v/vid/avc1/480x852/c.mp4", "video/mp4", 950_000),
            ),
        )
        assertEquals(listOf(1280, 852, 568), result.map { it.height })
    }

    @Test
    fun gifUsesOriginalSize() {
        val result = Qualities.select(
            listOf(RawVariant("https://video.twimg.com/tweet_video/G.mp4", "video/mp4", 0)),
            fallbackWidth = 480,
            fallbackHeight = 270,
        )
        assertEquals(1, result.size)
        assertEquals(480, result[0].width)
        assertEquals(270, result[0].height)
    }

    @Test
    fun missingContentTypeFallsBackToExtension() {
        val result = Qualities.select(
            listOf(
                RawVariant("https://v/vid/avc1/640x360/a.mp4", null, null),
                RawVariant("https://v/pl/list.m3u8", null, null),
            ),
        )
        assertEquals(1, result.size)
    }

    @Test
    fun emptyInput() {
        assertTrue(Qualities.select(emptyList()).isEmpty())
    }
}
