package ru.madhare74.xvideo

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.madhare74.xvideo.domain.FileNames
import ru.madhare74.xvideo.domain.RuFormat
import java.util.TimeZone

class FormatTest {

    @Test
    fun fileName() {
        assertEquals("X_nasa_1849012345678901234_1_720p.mp4", FileNames.build("nasa", "1849012345678901234", 1, 720))
        assertEquals("X_NASA_42_2_1080p.mp4", FileNames.build("@NASA", "42", 2, 1080))
        assertEquals("X_test_channel_7_1_src.mp4", FileNames.build("test_channel", "7", 1, 0))
        assertEquals("X_unknown_7_1_360p.mp4", FileNames.build("../", "7", 1, 360))
    }

    @Test
    fun uniqueFileName() {
        val name = "X_nasa_1_1_720p.mp4"
        assertEquals(name, FileNames.unique(name, emptyList()))
        assertEquals(name, FileNames.unique(name, listOf("X_nasa_1_1_1080p.mp4")))
        assertEquals("X_nasa_1_1_720p_2.mp4", FileNames.unique(name, listOf(name)))
        assertEquals("X_nasa_1_1_720p_2.mp4", FileNames.unique(name, listOf("x_NASA_1_1_720P.MP4")))
        assertEquals("X_nasa_1_1_720p_4.mp4", FileNames.unique(name, listOf(name, "X_nasa_1_1_720p_2.mp4", "X_nasa_1_1_720p_3.mp4")))
    }

    @Test
    fun megabytes() {
        assertEquals("12,3 МБ", RuFormat.megabytes(12_897_485))
        assertEquals("0,1 МБ", RuFormat.megabytes(1000))
        assertEquals("1 024,0 МБ".replace(" ", ""), RuFormat.megabytes(1024L * 1024 * 1024))
    }

    @Test
    fun duration() {
        assertEquals("0:07", RuFormat.duration(7_000))
        assertEquals("0:45", RuFormat.duration(45_120))
        assertEquals("12:34", RuFormat.duration(754_000))
        assertEquals("1:02:03", RuFormat.duration(3_723_000))
    }

    @Test
    fun progress() {
        assertEquals("50% · 5,0 из 10,0 МБ", RuFormat.progress(5L * 1024 * 1024, 10L * 1024 * 1024))
        assertEquals("2,0 МБ", RuFormat.progress(2L * 1024 * 1024, null))
    }

    @Test
    fun dateTime() {
        assertEquals("21.10.2026 10:30", RuFormat.dateTime(1_792_560_600_000L, TimeZone.getTimeZone("Asia/Yekaterinburg")))
    }
}
