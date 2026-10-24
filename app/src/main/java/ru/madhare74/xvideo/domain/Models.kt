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
    /** Нужен вход в аккаунт X (вход не выполнен). */
    Restricted,
    /** Вход выполнен, но X не отдаёт пост и аккаунту. */
    RestrictedForAccount,
    /** Вход в X устарел — нужно войти снова. */
    SessionExpired,
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
            "Видео доступно только после входа в аккаунт X: аккаунт закрыт или у поста возрастное ограничение. Войдите в аккаунт X, чтобы скачать его."
        AppError.RestrictedForAccount ->
            "X не показывает этот пост и вашему аккаунту: аккаунт закрыт и вы на него не подписаны, или в настройках X выключен показ деликатного содержимого."
        AppError.SessionExpired ->
            "Вход в X устарел. Войдите снова."
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

    /** Подсказка под сообщением, если она есть. */
    fun hint(error: AppError): String? = when (error) {
        AppError.RestrictedForAccount ->
            "Проверьте в приложении X: Настройки → Конфиденциальность и безопасность → Что вы видите → " +
                "«Показывать материалы деликатного характера». В аккаунте должен быть указан возраст 18+."
        else -> null
    }

    /** Для этих ошибок в сообщении показывается кнопка «Войти в X». */
    fun canLogin(error: AppError): Boolean = error == AppError.Restricted || error == AppError.SessionExpired

    /** Для ошибки сети подсказываем повторить поиск. */
    fun canRetrySearch(error: AppError): Boolean =
        error == AppError.Unavailable || error == AppError.FormatChanged || error == AppError.RestrictedForAccount
}
