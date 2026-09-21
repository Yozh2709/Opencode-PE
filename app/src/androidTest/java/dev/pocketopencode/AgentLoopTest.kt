package dev.pocketopencode

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** A deterministic local model exercises the real agent loop without paid API calls. */
@RunWith(AndroidJUnit4::class)
class AgentLoopTest {
    @Test fun realAgentRequestsPermissionWritesFileExecutesBashAndReturnsAnswer() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=Engine.get(context)
        val termux=InstrumentationRegistry.getArguments().getString("pocketBackend")=="termux"
        val prefs=context.getSharedPreferences("engine",0)
        val previousMode=prefs.getString("mode","auto")
        engine.stop()
        prefs.edit().putString("mode",if(termux) "termux" else "embedded").commit()
        val project=engine.workspace.create("agent-check-${System.currentTimeMillis()}")
        val target=project.resolve("hello.js")
        val directory=if(termux) "${TermuxBridge.HOME}/pocket-projects/${project.name}" else project.absolutePath
        val targetPath="$directory/hello.js"
        val toolUsed=AtomicBoolean(false)
        val bashUsed=AtomicBoolean(false)
        val server=MockWebServer()
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse {
                val body=JSONObject(request.body.readUtf8())
                val tools=body.optJSONArray("tools")?.objects().orEmpty()
                val tool=tools.mapNotNull{it.optJSONObject("function")}.firstOrNull{it.optString("name")=="write"}
                val delta=JSONObject()
                var finish="stop"
                if(tool!=null && toolUsed.compareAndSet(false,true)) {
                    val arguments=JSONObject().put("filePath",targetPath).put("content","console.log('hello from phone');\n")
                    delta.put("tool_calls",JSONArray().put(JSONObject().put("index",0).put("id","call_phone_write").put("type","function")
                        .put("function",JSONObject().put("name","write").put("arguments",arguments.toString()))))
                    finish="tool_calls"
                } else if(tools.any{it.optJSONObject("function")?.optString("name")=="bash"} && bashUsed.compareAndSet(false,true)) {
                    val arguments=JSONObject().put("command","node hello.js && git --version && command -v git && printf 'HOME=%s\\n' \"\$HOME\" && rg 'hello from phone' hello.js && printf 'bash-ok\\n' > bash-ok.txt")
                        .put("description","Run JavaScript, Git and ripgrep through the agent bash tool")
                    delta.put("tool_calls",JSONArray().put(JSONObject().put("index",0).put("id","call_phone_bash").put("type","function")
                        .put("function",JSONObject().put("name","bash").put("arguments",arguments.toString()))))
                    finish="tool_calls"
                } else delta.put("content",if(tools.isEmpty())"Phone check" else "Created hello.js on the phone.")
                val chunk=JSONObject().put("id","chatcmpl-phone").put("object","chat.completion.chunk").put("created",1).put("model","fixture")
                    .put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL)))
                val end=JSONObject().put("id","chatcmpl-phone").put("object","chat.completion.chunk").put("created",1).put("model","fixture")
                    .put("choices",JSONArray().put(JSONObject().put("index",0).put("delta",JSONObject()).put("finish_reason",finish)))
                return MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $chunk\n\ndata: $end\n\ndata: [DONE]\n\n")
            }
        }
        server.start()
        // Only this newly created fixture project is configured. User settings and keys are untouched.
        project.resolve("opencode.json").writeText(JSONObject().put("provider",JSONObject().put("pocketfixture",JSONObject()
            .put("npm","@ai-sdk/openai-compatible").put("name","Local test fixture")
            .put("options",JSONObject().put("baseURL",server.url("/v1").toString()).put("apiKey","fixture-only"))
            .put("models",JSONObject().put("fixture",JSONObject().put("name","Fixture").put("limit",JSONObject().put("context",8192).put("output",1024))))))
            .put("permission",JSONObject().put("*","ask").put("read","allow")).toString(2))
        try {
            val activity=InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            delay(1500) // Android must observe the resumed activity before cross-app RUN_COMMAND.
            if(termux) {
                val q=TermuxBridge::quote
                TermuxBridge.execute(context,"mkdir -p ${q(directory)} && printf %s ${q(project.resolve("opencode.json").readText())} > ${q("$directory/opencode.json")}")
            }
            engine.start()
            withTimeout(180_000){while(!engine.status.value.ready){engine.status.value.error?.let{error(it+"\n"+engine.logs.value)};delay(500)}}
            val api=checkNotNull(engine.api)
            assertEquals("1.18.31", JSONObject(api.call("/global/health")).getString("version"))
            val providers=api.providers(directory).getJSONArray("all").objects()
            assertTrue(providers.first { it.getString("id")=="openai" }.getJSONObject("models").has("gpt-6-astra"))
            assertTrue(providers.first { it.getString("id")=="opencode" }.getJSONObject("models").has("muse-spark-1.3-contributor-free"))
            val id=api.create(directory)
            var browser:android.webkit.WebView?=null
            suspend fun evaluate(script:String):String = suspendCancellableCoroutine { continuation ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    checkNotNull(browser).evaluateJavascript(script) { result -> if(continuation.isActive)continuation.resumeWith(Result.success(result)) }
                }
            }
            if(!termux) {
                fun findWeb(view:android.view.View):android.webkit.WebView? {
                    if(view is android.webkit.WebView)return view
                    if(view is android.view.ViewGroup)for(i in 0 until view.childCount)findWeb(view.getChildAt(i))?.let { return it }
                    return null
                }
                val server=android.util.Base64.encodeToString(api.webOrigin.toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    browser=checkNotNull(findWeb(activity.window.decorView))
                    browser!!.loadUrl(api.webUrl.replace("/?auth_token=","/server/$server/session/$id?auth_token="))
                }
            }
            if(!termux) {
                withTimeout(30_000) { while(evaluate("!!window.pocketMobile && !!document.querySelector('[data-component=prompt-input-v2]')")!="true")delay(300) }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.window.decorView.findViewWithTag<android.view.View>("settings").performClick() }
                withTimeout(30_000) { while(evaluate("!!document.querySelector('[data-action=settings-auto-accept-permissions] input:not(:disabled)')")!="true")delay(300) }
                assertEquals("2",evaluate("document.querySelectorAll('[data-pocket-settings]').length"))
                evaluate("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}))")
            }
            api.prompt(directory,id,"Create hello.js that prints hello from phone.","pocketfixture","fixture")
            var permissionSeen=false
            var bashPermissionSeen=false
            var bashOutput=""
            withTimeout(120_000) {
                while(true) {
                    val permissions=api.permissions(directory).objects().filter{it.optString("sessionID")==id}
                    for(p in permissions){
                        permissionSeen=true
                        if(p.optString("permission")=="bash")bashPermissionSeen=true
                        if(termux)api.permission(directory,p.getString("id"),true)
                        else {
                            withTimeout(30_000) {
                                var clicked=false
                                while(!clicked) {
                                    val expected=JSONObject.quote(p.getString("id"))
                                    val ready=evaluate("(()=>{const p=document.getElementById('pocket-permission');return (p?.dataset.requestId===${expected} && !!p.querySelector('button[data-reply=once]:not(:disabled)')) || !!document.querySelector('[data-component=dock-prompt][data-kind=permission]:not(#pocket-permission) button[data-variant=primary]:not(:disabled)')})()") == "true"
                                    if(ready) {
                                        if(!bashPermissionSeen) {
                                            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                                                context.cacheDir.resolve("permission-web-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                                                bitmap.recycle()
                                            }
                                        }
                                        clicked=evaluate("(()=>{document.querySelector('#pocket-permission button[data-reply=once], [data-component=dock-prompt][data-kind=permission] button[data-variant=primary]').click();return true})()") == "true"
                                    }
                                    if(!clicked)delay(300)
                                }
                            }
                        }
                    }
                    val messages=api.messages(directory,id).objects()
                    messages.map{it.getJSONObject("info")}.firstOrNull{it.has("error")}?.let{error(it.getJSONObject("error").toString())}
                    val parts=messages.flatMap{it.optJSONArray("parts")?.objects().orEmpty()}
                    for(part in parts.filter{it.optString("type")=="tool"}) {
                        val state=part.getJSONObject("state")
                        if(state.optString("status")=="error")error("${part.optString("tool")}: ${state.optString("error")}")
                        if(part.optString("tool")=="bash" && state.optString("status")=="completed")bashOutput=state.optString("output")
                    }
                    if(parts.any{it.optString("type")=="text" && it.optString("text")=="Created hello.js on the phone."})break
                    delay(500)
                }
            }
            assertTrue("Agent did not request the write tool",toolUsed.get())
            assertTrue("Write permission was not requested",permissionSeen)
            assertTrue("Agent did not request bash",bashUsed.get())
            assertTrue("Bash permission was not requested",bashPermissionSeen)
            assertTrue(bashOutput,bashOutput.contains("hello from phone") && bashOutput.contains("git version"))
            if(termux) {
                assertTrue(bashOutput,bashOutput.contains("${TermuxBridge.PREFIX}/bin/git")&&bashOutput.contains("HOME=${TermuxBridge.HOME}"))
                assertEquals("bash-ok\n",TermuxBridge.execute(context,"cat bash-ok.txt",directory))
                assertEquals("console.log('hello from phone');\n",TermuxBridge.execute(context,"cat hello.js",directory))
            } else {
                assertEquals("bash-ok\n",project.resolve("bash-ok.txt").readText())
                assertEquals("console.log('hello from phone');\n",target.readText())
            }
            withTimeout(60_000){while(!api.messages(directory,id).toString().contains("Created hello.js on the phone."))delay(500)}
            api.call("/session/$id","DELETE",directory=directory)
        } finally {
            engine.stop();server.shutdown()
            prefs.edit().putString("mode",previousMode).commit()
            if(termux) {
                check(directory=="${TermuxBridge.HOME}/pocket-projects/${project.name}" && project.name.matches(Regex("agent-check-[0-9]+")))
                try { TermuxBridge.execute(context,"rm -rf -- ${TermuxBridge.quote(directory)}") }
                catch (e: Exception) { android.util.Log.w("AgentLoopTest", "Fixture cleanup failed: $directory", e) }
            }
            project.deleteRecursively()
        }
    }
}
