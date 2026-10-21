package ru.madhare74.xvideo.domain

/** Пост X с найденными в нём видео. */
data class Post(
    val id: String,
    val authorName: String,
    val screenName: String,
    val text: String,
    val videos: List<PostVideo>,
)

/** Одно видео (или GIF) из поста. [index] — номер среди видео поста, начиная с 1. */
data class PostVideo(
    val index: Int,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val isGif: Boolean,
    val variants: List<VideoVariant>,
) {
    val best: VideoVariant? get() = variants.firstOrNull()
}

/** Один вариант качества видео — прямая ссылка на MP4 на CDN X. */
data class VideoVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bitrate: Long,
) {
    val resolution: String get() = if (width > 0 && height > 0) "$width×$height" else "Исходное качество"
}

/** Вариант видео, как он записан в ответе X, до отбора и сортировки. */
data class RawVariant(
    val url: String,
    val contentType: String?,
    val bitrate: Long?,
)

/** Ошибки, которые видит пользователь. Каждой соответствует своё сообщение — см. [Messages]. */
enum class AppError {
    InvalidLink,
    NotFound,
    Restricted,
    NoVideo,
    Unavailable,
    FormatChanged,
    NoSpace,
    DownloadFailed,
}

object Messages {
    fun text(error: AppError): String = when (error) {
        AppError.InvalidLink ->
            "Это не похоже на ссылку на пост X. Нужна ссылка вида x.com/имя/status/123…"
        AppError.NotFound ->
            "Пост не найден или удалён."
        AppError.Restricted ->
            "Видео доступно только после входа в аккаунт X: аккаунт закрыт или у поста возрастное ограничение. Такие посты приложение не поддерживает."
        AppError.NoVideo ->
            "В этом посте нет видео."
        AppError.Unavailable ->
            "X недоступен. Проверьте интернет и включён ли VPN."
        AppError.FormatChanged ->
            "X изменил формат данных, нужно обновление приложения."
        AppError.NoSpace ->
            "Недостаточно места на телефоне."
        AppError.DownloadFailed ->
            "Ошибка при скачивании. Проверьте соединение и VPN и нажмите «Повторить»."
    }

    /** Для ошибки сети подсказываем повторить поиск. */
    fun canRetrySearch(error: AppError): Boolean =
        error == AppError.Unavailable || error == AppError.FormatChanged
}
