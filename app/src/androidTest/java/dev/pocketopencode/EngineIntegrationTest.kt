package dev.pocketopencode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineIntegrationTest {
    @Test fun embeddedRuntimeCreatesProjectRunsShellAndRestoresSession() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=Engine.get(context)
        val name="integration-${System.currentTimeMillis()}"
        val project=engine.workspace.create(name)
        try {
            engine.start()
            withTimeout(180_000){while(!engine.status.value.ready){engine.status.value.error?.let{error(it+"\n"+engine.logs.value)};delay(500)}}
            val api=checkNotNull(engine.api)
            assertTrue(api.health())
            val tools=engine.command(listOf("/system/bin/sh","-c","git --version && node --version && rg --version && bun --version"),project)
            assertTrue(tools.contains("git version"));assertTrue(tools.contains("ripgrep"));assertTrue(tools.contains("1.4.2"))
            val id=api.create(project.absolutePath)
            val result=api.call("/session/$id/shell","POST",JSONObject()
                .put("agent","build").put("model",JSONObject().put("providerID","opencode").put("modelID","test-no-network"))
                .put("command","printf 'console.log(6 * 7)\\n' > answer.js && node answer.js"),project.absolutePath)
            assertTrue(result,result.contains("42"));assertTrue(project.resolve("answer.js").isFile)
            assertTrue(api.messages(project.absolutePath,id).length()>=2)
            engine.stop();delay(1000);engine.start()
            withTimeout(180_000){while(!engine.status.value.ready){engine.status.value.error?.let{error(it+"\n"+engine.logs.value)};delay(500)}}
            assertTrue(checkNotNull(engine.api).sessions(project.absolutePath).objects().any{it.getString("id")==id})
            assertEquals("console.log(6 * 7)\n",project.resolve("answer.js").readText())
        } finally {engine.stop()}
    }
}
