package dev.pocketopencode

import org.junit.Assert.*
import org.junit.Test

class NativeLanguageTest {
    @Test fun languageFallbackAndCookieValidation() {
        assertEquals("ru", nativeLanguage("ru-RU"))
        assertEquals("ru", nativeLanguage("RU_ru"))
        assertEquals("en", nativeLanguage("fr"))
        assertEquals("en", nativeLanguage(""))
        assertNull(OpenCodeLanguage.fromCookie(null))
        assertNull(OpenCodeLanguage.fromCookie("other_locale=ru; oc_locale=unknown"))
        assertNull(OpenCodeLanguage.fromCookie("oc_locale=ru<script>"))
        assertEquals("ru", OpenCodeLanguage.fromCookie("other=value; oc_locale=ru; another=value"))
        assertEquals("zht", OpenCodeLanguage.fromCookie("oc_locale=zht"))
    }

    @Test fun everyTranslationHasMatchingPlaceholdersAndFormatsArgumentsLiterally() {
        val placeholders=Regex("%[0-9]+\\\$s")
        UiText.entries.forEach { key ->
            assertTrue(key.name, key.en.isNotBlank())
            assertTrue(key.name, key.ru.isNotBlank())
            assertEquals(key.name, placeholders.findAll(key.en).map { it.value }.toList(), placeholders.findAll(key.ru).map { it.value }.toList())
            val args=Array<Any>(placeholders.findAll(key.en).count()) { "project 'with quotes' %s \$HOME" }
            for(locale in listOf("en","ru","de")) {
                val value=key.format(locale,*args)
                if(args.isNotEmpty()) assertTrue(key.name,value.contains(args[0].toString()))
            }
        }
        assertEquals("This is a binary file",UiText.BinaryFile.format("de"))
        assertTrue(UiText.NpmHelp.format("en").contains("\$NPM_CLI"))
    }
}
