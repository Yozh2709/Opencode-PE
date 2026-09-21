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

class NewChatTest {
    @Test fun visibleButtonCreatesNewSessionInCurrentRouteWithoutChangingPreviousChats() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val engine=Engine.get(context)
        val termux=TermuxBridge.preferred(context)
        val name="new-chat-check-${System.currentTimeMillis()}"
        val dirs=if(termux) listOf("${TermuxBridge.HOME}/pocket-projects/$name-a","${TermuxBridge.HOME}/pocket-projects/$name-b")
            else listOf(engine.workspace.create("$name-a").absolutePath,engine.workspace.create("$name-b").absolutePath)
        if(termux) TermuxBridge.execute(context,"mkdir -p "+dirs.joinToString(" ",transform=TermuxBridge::quote))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("project",dirs[0]))
        fun find(view:View):WebView? {
            if(view is WebView)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        lateinit var web:WebView
        lateinit var button:Button
        instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); web=checkNotNull(find(activity.window.decorView)); button=activity.window.decorView.findViewWithTag("new-chat") }
        fun evaluate(script:String):String {
            val done=CountDownLatch(1); var value=""
            instrumentation.runOnMainSync { web.evaluateJavascript(script) { value=it;done.countDown() } }
            // A full-page navigation can discard an in-flight JS callback. Retry while loading.
            if(!done.await(5,TimeUnit.SECONDS))return "null"
            return value
        }
        fun slug(dir:String)=android.util.Base64.encodeToString(dir.toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        fun sessionId()=(JSONTokener(evaluate("location.pathname.split('/').pop()")).nextValue() as? String).orEmpty()
        suspend fun waitForPage(directory:String,previous:String?=null) {
            withTimeout(180_000) { while(true) {
                engine.status.value.error?.let { error(it) }
                val path=evaluate("location.pathname")
                if(path.contains(slug(directory))&&sessionId().startsWith("ses_")&&sessionId()!=previous&&evaluate("!!document.querySelector('[contenteditable]')")=="true")break
                delay(400)
            } }
        }
        val sessions=mutableListOf<Pair<String,String>>()
        try {
            waitForPage(dirs[0])
            val api=checkNotNull(engine.api)
            val first=sessionId(); sessions+=dirs[0] to first
            val original=api.call("/session/$first",directory=dirs[0])
            assertEquals(1,api.sessions(dirs[0]).length())
            instrumentation.runOnMainSync {
                assertTrue(button.isShown&&button.isEnabled)
                assertEquals(tr(UiText.NewChat),button.contentDescription)
                button.performClick();button.performClick() // Rapid taps must create exactly one session.
            }
            waitForPage(dirs[0],first)
            sessions+=dirs[0] to sessionId()
            assertEquals(2,api.sessions(dirs[0]).length())
            assertEquals(original,api.call("/session/$first",directory=dirs[0]))
            // Navigate inside the web app without changing Android's original project Intent.
            val second=api.create(dirs[1]);sessions+=dirs[1] to second
            val url=api.webUrl.replace("/?auth_token=","/${slug(dirs[1])}/session/$second?auth_token=")
            instrumentation.runOnMainSync { web.loadUrl(url) }
            waitForPage(dirs[1])
            instrumentation.runOnMainSync { button.performClick() }
            waitForPage(dirs[1],second)
            sessions+=dirs[1] to sessionId()
            assertEquals(2,api.sessions(dirs[1]).length())
            assertEquals(2,api.sessions(dirs[0]).length())
            assertEquals("false",evaluate("document.documentElement.scrollWidth>innerWidth+2"))
            assertEquals("false",evaluate("!!document.querySelector('textarea[readonly]')"))
        } finally {
            engine.api?.let { api -> sessions.forEach { (dir,id) -> runCatching { api.call("/session/$id","DELETE",directory=dir) } } }
            engine.stop()
            if(termux) TermuxBridge.execute(context,"rmdir -- "+dirs.joinToString(" ",transform=TermuxBridge::quote))
            else dirs.forEach { java.io.File(it).deleteRecursively() }
        }
    }
}
