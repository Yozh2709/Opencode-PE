package dev.pocketopencode

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject

class PocketApplication : Application() {
    override fun onCreate() { super.onCreate(); LanguagePreferences.initialize(this) }
}

/** Only a validated locale crosses from the trusted local web origin to Android. */
object LanguagePreferences {
    private lateinit var preferences: android.content.SharedPreferences
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)
        saved()?.let(NativeLanguage::select)
    }
    private fun saved() = preferences.getString("opencode_locale", null)?.takeIf { it in OpenCodeLanguage.supported }

    fun readFromWeb(origin: String) {
        if (origin.isBlank()) return
        val locale = OpenCodeLanguage.fromCookie(CookieManager.getInstance().getCookie(origin)) ?: return
        if (saved() != locale) preferences.edit().putString("opencode_locale", locale).apply()
        NativeLanguage.select(locale)
    }

    /** Seed the same choice when switching between embedded and Termux web origins.
     * Runs before the upstream module; does not reload the current editor or restart the core.
     */
    fun seedHtml(html: String): String {
        val locale = saved() ?: return html
        val value = JSONObject.quote(locale)
        val script = """<script>(function(){try{const k="opencode.global.dat:language";let s={};try{s=JSON.parse(localStorage.getItem(k))||{}}catch(_){}localStorage.setItem(k,JSON.stringify({...s,locale:$value}));document.cookie="oc_locale="+$value+"; Path=/; Max-Age=31536000; SameSite=Lax"}catch(_){}})()</script>"""
        return html.replaceFirst("<head>", "<head>$script")
    }
}
