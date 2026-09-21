package dev.pocketopencode

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No Android dependency: messages also work in file/transport unit tests. */
object NativeLanguage {
    private val current = MutableStateFlow(Locale.getDefault().toLanguageTag())
    val locale = current.asStateFlow()
    fun select(locale: String) { current.value = locale }
}

object OpenCodeLanguage {
    val supported = setOf("en", "zh", "zht", "ko", "de", "es", "fr", "da", "ja", "pl", "ru", "uk", "bs", "ar", "no", "br", "th", "tr", "hi", "nl", "id", "vi", "it", "ur", "pa", "az", "fi", "sv", "am", "bg", "bn", "ca", "cs", "dv", "dz", "el", "et", "fa", "fo", "hr", "hu", "hy", "is", "ka", "km", "lo", "lt", "lv", "mk", "mn", "ms", "my", "ne", "ro", "si", "sk", "sl", "sq", "sr", "tg", "tk", "uz")

    // OpenCode's language context writes this cookie whenever its locale changes.
    fun fromCookie(cookie: String?): String? = cookie?.split(';')
        ?.map { it.trim().split('=', limit = 2) }
        ?.firstOrNull { it.size == 2 && it[0] == "oc_locale" }
        ?.get(1)?.takeIf { it in supported }
}
