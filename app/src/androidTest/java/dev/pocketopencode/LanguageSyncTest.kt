package dev.pocketopencode

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanguageSyncTest {
    @Test fun followsWebLocaleWithoutRestartingCoreAndSeedsOtherOrigins() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val engine=Engine.get(context)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun find(view: View): WebView? {
            if(view is WebView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var web: WebView
        lateinit var button: Button
        instrumentation.runOnMainSync {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            web=checkNotNull(find(activity.window.decorView))
            button=activity.window.decorView.findViewWithTag("new-chat")
        }
        fun evaluate(script: String): String {
            val latch=CountDownLatch(1); var result="null"
            instrumentation.runOnMainSync { web.evaluateJavascript(script) { result=it; latch.countDown() } }
            latch.await(5,TimeUnit.SECONDS)
            return result
        }
        suspend fun waitUntil(check: () -> Boolean) { withTimeout(180_000) { while(!check()) { engine.status.value.error?.let { error(it) }; delay(250) } } }
        // An old cookie can exist before the new page mounts. Wait for the actual
        // language context, otherwise startup can overwrite our first test signal.
        waitUntil { engine.status.value.ready && evaluate("document.querySelectorAll('button').length>8 && document.documentElement.lang===JSON.parse(localStorage.getItem('opencode.global.dat:language')||'{}').locale")=="true" }
        println("OpenCode language context ready")
        val original=JSONTokener(evaluate("document.documentElement.lang")).nextValue() as String
        val api=checkNotNull(engine.api)
        evaluate("window.__pocketLocaleCheck=true")
        // Mimic the existing language context's cookie signal, without navigation or model calls.
        // The separate on-device GUI check uses the actual settings menu.
        try {
            for(locale in listOf("en","ru","de")) {
                evaluate("document.cookie='oc_locale=$locale; Path=/; Max-Age=31536000; SameSite=Lax'")
                waitUntil { NativeLanguage.locale.value==locale }
                println("Native locale synchronized: $locale")
                instrumentation.runOnMainSync {
                    assertEquals(UiText.Chat.format(locale),button.text)
                    assertEquals(UiText.NewChat.format(locale),button.contentDescription)
                }
                assertSame(api,engine.api)
                // The upstream home page may restore its previous SPA route on startup.
                // A window sentinel specifically detects a destructive full-page reload.
                assertEquals("true",evaluate("window.__pocketLocaleCheck===true"))
                assertTrue(LanguagePreferences.seedHtml("<html><head></head></html>").contains("locale:\"$locale\""))
                // The seed merges stored data and runs before the upstream entry script.
                val seeded=LanguagePreferences.seedHtml("<head><script type=\"module\"></script>")
                assertTrue(seeded.indexOf("opencode.global.dat:language")<seeded.indexOf("type=\"module\""))
            }
        } finally {
            evaluate("delete window.__pocketLocaleCheck")
            evaluate("document.cookie='oc_locale=$original; Path=/; Max-Age=31536000; SameSite=Lax'")
            instrumentation.runOnMainSync { LanguagePreferences.readFromWeb(api.webOrigin) }
            assertEquals(original,NativeLanguage.locale.value)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
