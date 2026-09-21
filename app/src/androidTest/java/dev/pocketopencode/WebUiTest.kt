package dev.pocketopencode

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener

@RunWith(AndroidJUnit4::class)
class WebUiTest {
    @Test fun bundledOfficialUiLoadsProjectAndComposer() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val project=Engine.get(context).workspace.create("web-ui-${System.currentTimeMillis()}")
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("project",project.absolutePath))
        fun find(view:View):WebView? {
            if(view is WebView)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        var web:WebView?=null
        instrumentation.runOnMainSync { web=find(activity.window.decorView) }
        assertNotNull(web)
        fun evaluate(script:String):String {
            val done=CountDownLatch(1);var result=""
            instrumentation.runOnMainSync { web!!.evaluateJavascript(script){result=it;done.countDown()} }
            assertTrue(done.await(10,TimeUnit.SECONDS));return result
        }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150)
        val encoded=android.util.Base64.encodeToString(project.absolutePath.toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        var text=""
        while(System.nanoTime()<deadline){
            text=evaluate("document.getElementById('root')?.innerText || ''")
            if(evaluate("location.pathname").contains(encoded)&&evaluate("!!document.querySelector('[contenteditable=\"true\"],textarea')")=="true")break
            Thread.sleep(1000)
        }
        assertTrue(evaluate("document.title").contains("OpenCode"))
        assertTrue(text,evaluate("location.pathname").contains(encoded))
        assertEquals(text,"true",evaluate("!!document.querySelector('[contenteditable=\"true\"],textarea')"))
        assertEquals("false",evaluate("document.documentElement.scrollWidth > innerWidth + 2"))
        // Empty sessions did not exercise virtualized message rendering. Add real user/tool
        // messages through OpenCode's shell endpoint, without sending a model request.
        val id=JSONTokener(evaluate("location.pathname.split('/').pop()")).nextValue() as String
        runBlocking {
            val output=checkNotNull(Engine.get(context).api).call("/session/$id/shell","POST",JSONObject()
                .put("agent","build").put("model",JSONObject().put("providerID","opencode").put("modelID","big-pickle"))
                .put("command","printf 'timeline regression 42\\n'"),project.absolutePath)
            assertTrue(output,output.contains("timeline regression 42"))
        }
        val messageDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(System.nanoTime()<messageDeadline){
            text=evaluate("document.getElementById('root')?.innerText || ''")
            if(evaluate("document.querySelectorAll('[data-component=\"session-turn\"]').length > 0")=="true")break
            Thread.sleep(500)
        }
        Thread.sleep(3000) // ResizeObserver/virtualizer updates run after initial DOM insertion.
        text=evaluate("document.getElementById('root')?.innerText || ''")
        // Shell output is collapsed by the upstream GUI; verify rendered turns rather
        // than requiring the collapsed command text to appear in innerText.
        assertEquals(text,"true",evaluate("document.querySelectorAll('[data-component=\"session-turn\"]').length > 0"))
        assertEquals(text,"true",evaluate("!!document.querySelector('[contenteditable=\"true\"]')"))
        assertEquals("false",evaluate("!!document.querySelector('textarea[readonly]')"))
    }
}
