package ru.madhare74.xvideo.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import ru.madhare74.xvideo.domain.FileNames
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Файл, в который идёт запись. До [commit] он не виден в галерее; [discard] удаляет его. */
interface SaveTarget {
    val fileName: String
    fun open(): OutputStream
    /** Делает файл видимым и возвращает его адрес content://. */
    fun commit(): Uri
    fun discard()
}

/**
 * Сохранение в общую папку `Movies/X Downloader`, видную в «Галерее» и «Моих файлах».
 * Android 10+ — через MediaStore без разрешений; Android 8–9 — файлом в папке Movies
 * (нужно разрешение на запись в память) с регистрацией в MediaStore.
 */
class MediaSaver(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    fun create(baseName: String): SaveTarget {
        val name = FileNames.unique(baseName, existingNames())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreTarget(name) else LegacyTarget(name)
    }

    fun existingNames(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val names = HashSet<String>()
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            try {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$RELATIVE_PATH%"),
                    null,
                )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(names::add) }
            } catch (e: Exception) {
                // Нет доступа к списку — опираемся на то, что MediaStore сам не перезапишет файл.
            }
            names
        } else {
            legacyDir().list()?.toSet().orEmpty()
        }

    @Suppress("DEPRECATION")
    private fun legacyDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FileNames.FOLDER)

    private inner class MediaStoreTarget(override val fileName: String) : SaveTarget {
        private val uri: Uri

        init {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore insert failed")
        }

        override fun open(): OutputStream =
            resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open $uri")

        override fun commit(): Uri {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, values, null, null)
            return uri
        }

        override fun discard() {
            try {
                resolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Файл мог и не создаться.
            }
        }
    }

    @Suppress("DEPRECATION")
    private inner class LegacyTarget(override val fileName: String) : SaveTarget {
        private val file = File(legacyDir().apply { mkdirs() }, fileName)

        override fun open(): OutputStream = FileOutputStream(file)

        override fun commit(): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.SIZE, file.length())
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(file)
        }

        override fun discard() {
            file.delete()
        }
    }

    /** Есть ли ещё файл по этому адресу (его могли удалить вне приложения). */
    fun exists(uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            File(uri.path.orEmpty()).exists()
        } else {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_MOVIES}/${FileNames.FOLDER}/"
    }
}
