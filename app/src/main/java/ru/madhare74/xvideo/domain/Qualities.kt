package ru.madhare74.xvideo.domain

/**
 * Отбор вариантов качества: только MP4 (потоки HLS .m3u8 пропускаются), разрешение берётся
 * из пути ссылки (…/vid/avc1/1280x720/…), а если его там нет (GIF) — из размеров исходника.
 * Варианты с одинаковым разрешением схлопываются в один с наибольшим битрейтом.
 * Сортировка — от лучшего к худшему.
 */
object Qualities {

    private val sizeInPath = Regex("""/(\d{2,5})x(\d{2,5})/""")

    fun select(raw: List<RawVariant>, fallbackWidth: Int = 0, fallbackHeight: Int = 0): List<VideoVariant> {
        val mp4 = raw.filter { v ->
            v.url.startsWith("http", ignoreCase = true) && isMp4(v)
        }
        val parsed = mp4.map { v ->
            val m = sizeInPath.find(v.url.substringBefore('?'))
            val w = m?.groupValues?.get(1)?.toIntOrNull() ?: fallbackWidth
            val h = m?.groupValues?.get(2)?.toIntOrNull() ?: fallbackHeight
            VideoVariant(url = v.url, width = w, height = h, bitrate = v.bitrate ?: 0L)
        }
        return parsed
            .groupBy { it.width to it.height }
            .map { (_, same) -> same.maxBy { it.bitrate } }
            .sortedWith(
                compareByDescending<VideoVariant> { it.width.toLong() * it.height }
                    .thenByDescending { it.bitrate },
            )
    }

    private fun isMp4(v: RawVariant): Boolean {
        val type = v.contentType?.lowercase()
        if (type != null) return type == "video/mp4"
        return v.url.substringBefore('?').endsWith(".mp4", ignoreCase = true)
    }
}
