package ru.madhare74.xvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.madhare74.xvideo.domain.AppError
import ru.madhare74.xvideo.domain.Messages

class MessagesTest {

    @Test
    fun everyErrorHasItsOwnMessage() {
        val texts = AppError.entries.map { Messages.text(it) }
        assertEquals(AppError.entries.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
    }

    @Test
    fun keyMessages() {
        assertEquals("В этом посте нет видео.", Messages.text(AppError.NoVideo))
        assertEquals("X изменил формат данных, нужно обновление приложения.", Messages.text(AppError.FormatChanged))
        assertTrue(Messages.text(AppError.Unavailable).contains("VPN"))
        assertTrue(Messages.text(AppError.Restricted).contains("вход"))
        assertTrue(Messages.text(AppError.NoSpace).contains("места"))
        assertTrue(Messages.text(AppError.DownloadFailed).contains("Повторить"))
    }

    @Test
    fun retryOnlyForTransientErrors() {
        assertTrue(Messages.canRetrySearch(AppError.Unavailable))
        assertFalse(Messages.canRetrySearch(AppError.InvalidLink))
        assertFalse(Messages.canRetrySearch(AppError.NoVideo))
    }
}
