package ru.madhare74.xvideo.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FileNames {

    const val FOLDER = "X Downloader"

    /** `X_<ник>_<id поста>_<номер видео>_<высота>p.mp4`, например `X_nasa_1849…_1_720p.mp4`. */
    fun build(screenName: String, postId: String, videoIndex: Int, height: Int): String {
        val nick = screenName.trim().removePrefix("@").replace(Regex("[^A-Za-z0-9_]"), "").ifEmpty { "unknown" }
        val id = postId.filter { it.isDigit() }.ifEmpty { "0" }
        val quality = if (height > 0) "${height}p" else "src"
        return "X_${nick}_${id}_${videoIndex}_$quality.mp4"
    }

    /**
     * Если файл с таким именем уже есть, к имени добавляется номер: `…_720p_2.mp4`, `…_720p_3.mp4`.
     * Сравнение без учёта регистра — как в файловой системе телефона.
     */
    fun unique(name: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in taken) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "${base}_$n" else "${base}_$n.$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }
}

object RuFormat {

    /** Размер файла в мегабайтах с одним знаком после запятой: «12,3 МБ». */
    fun megabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        val shown = if (mb in 0.0..0.1 && bytes > 0) 0.1 else mb
        return String.format(Locale.ROOT, "%.1f", shown).replace('.', ',') + " МБ"
    }

    /** Длительность видео: «0:07», «12:34», «1:02:03». */
    fun duration(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun dateTime(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(epochMs))

    /** «45% · 5,1 из 12,3 МБ» или «5,1 МБ», если размер заранее неизвестен. */
    fun progress(done: Long, total: Long?): String =
        if (total != null && total > 0) {
            val percent = (done * 100 / total).coerceIn(0, 100)
            "$percent% · ${megabytes(done).removeSuffix(" МБ")} из ${megabytes(total)}"
        } else {
            megabytes(done)
        }
}
