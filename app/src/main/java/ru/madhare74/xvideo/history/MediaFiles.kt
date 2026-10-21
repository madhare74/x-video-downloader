package ru.madhare74.xvideo.history

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import ru.madhare74.xvideo.download.MediaSaver
import java.io.File

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    /** Android просит подтверждения пользователя (файл создан до переустановки приложения). */
    data class NeedsConfirmation(val sender: IntentSender) : DeleteResult
    data object Failed : DeleteResult
}

/** Операции с сохранёнными файлами — отдельно, чтобы в тестах их можно было подменить. */
interface MediaFiles {
    fun exists(uri: String): Boolean
    fun delete(uri: String): DeleteResult
}

class AndroidMediaFiles(private val context: Context, private val saver: MediaSaver) : MediaFiles {

    override fun exists(uri: String): Boolean = saver.exists(Uri.parse(uri))

    override fun delete(uri: String): DeleteResult {
        val u = Uri.parse(uri)
        return try {
            if (u.scheme == "file") {
                File(u.path.orEmpty()).delete()
                DeleteResult.Deleted
            } else {
                context.contentResolver.delete(u, null, null)
                DeleteResult.Deleted
            }
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    DeleteResult.NeedsConfirmation(
                        MediaStore.createDeleteRequest(context.contentResolver, listOf(u)).intentSender,
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    DeleteResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
                else -> DeleteResult.Failed
            }
        } catch (e: Exception) {
            DeleteResult.Failed
        }
    }
}
