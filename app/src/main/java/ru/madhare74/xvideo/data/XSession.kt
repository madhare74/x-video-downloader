package ru.madhare74.xvideo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Данные сессии X после входа: cookie `auth_token` (сам вход) и `ct0` (защита от подделки
 * запросов, X сообщает её сам и может менять). Пароль сюда не попадает никогда.
 */
data class XCookies(val authToken: String, val ct0: String?) {

    /** Заголовок Cookie для запросов к x.com. */
    fun header(): String = buildString {
        append("auth_token=").append(authToken)
        if (!ct0.isNullOrEmpty()) append("; ct0=").append(ct0)
    }

    override fun toString() = "XCookies(auth_token=***, ct0=${if (ct0 == null) "нет" else "***"})"

    companion object {
        private val tokenPattern = Regex("[0-9a-fA-F]{20,80}")
        private val ct0Pattern = Regex("[0-9a-zA-Z]{16,200}")

        /** Из строки cookies, как её отдаёт CookieManager: `a=1; auth_token=…; ct0=…`. */
        fun parse(cookieHeader: String?): XCookies? {
            if (cookieHeader.isNullOrBlank()) return null
            val map = cookieHeader.split(';')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
                }
                .toMap()
            val token = map["auth_token"]?.takeIf { tokenPattern.matches(it) } ?: return null
            return XCookies(token, map["ct0"]?.takeIf { ct0Pattern.matches(it) })
        }

        /**
         * Ручной ввод: значение cookie `auth_token`, допускается вставка вместе с именем
         * (`auth_token=…`), в кавычках или целой строкой cookies из браузера.
         */
        fun fromManualInput(text: String): XCookies? {
            val trimmed = text.trim().trim('"', '\'', ' ')
            if (trimmed.contains('=')) return parse(trimmed)
            return if (tokenPattern.matches(trimmed)) XCookies(trimmed, null) else null
        }
    }
}

/** Где хранится сессия: в приложении — закрытые настройки, в тестах — память. */
interface SessionStore {
    fun load(): XCookies?
    fun save(cookies: XCookies?)
}

class InMemorySessionStore(private var value: XCookies? = null) : SessionStore {
    override fun load() = value
    override fun save(cookies: XCookies?) {
        value = cookies
    }
}

/** Закрытые настройки приложения: доступны только этому приложению. */
class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("x_session", Context.MODE_PRIVATE)

    override fun load(): XCookies? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return XCookies(token, prefs.getString(KEY_CT0, null))
    }

    override fun save(cookies: XCookies?) {
        prefs.edit().apply {
            if (cookies == null) clear() else {
                putString(KEY_TOKEN, cookies.authToken)
                putString(KEY_CT0, cookies.ct0)
            }
        }.apply()
    }

    private companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_CT0 = "ct0"
    }
}

/** Текущее состояние входа в X. */
class XSession(private val store: SessionStore) {

    private val _cookies = MutableStateFlow(store.load())
    val cookies: StateFlow<XCookies?> = _cookies.asStateFlow()
    val isLoggedIn: Boolean get() = _cookies.value != null

    /** Вызывается при выходе — например, чтобы очистить cookies встроенного браузера. */
    var onLogout: () -> Unit = {}

    fun login(cookies: XCookies) = set(cookies)

    /** X прислал новую `ct0` — сохраняем, вход при этом не меняется. */
    fun updateCt0(ct0: String) {
        val current = _cookies.value ?: return
        if (current.ct0 != ct0) set(current.copy(ct0 = ct0))
    }

    fun logout() {
        set(null)
        onLogout()
    }

    private fun set(value: XCookies?) {
        store.save(value)
        _cookies.value = value
    }
}
