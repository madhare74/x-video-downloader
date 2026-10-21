package ru.madhare74.xvideo

/** Сохранённые образцы ответов X из src/sharedTest/fixtures. */
object Fixtures {
    fun text(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Нет образца $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
