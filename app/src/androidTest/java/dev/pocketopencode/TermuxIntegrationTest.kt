package dev.pocketopencode

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TermuxIntegrationTest {
    @Test fun startsInTermuxAndRunsTools() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Termux must be installed",TermuxBridge.installed(context))
        assertTrue("Grant RUN_COMMAND",TermuxBridge.permitted(context))
        context.getSharedPreferences("engine",0).edit().putString("mode","auto").commit()
        Engine.get(context).stop()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val probe=TermuxBridge.execute(context,"printf 'HOME=%s\\n' \"\$HOME\"; command -v git; git --version; node --version",timeoutMs=30_000)
        println("TERMUX_PROBE: $probe")
        assertTrue(probe.contains(TermuxBridge.HOME))
        assertTrue(probe.contains("${TermuxBridge.PREFIX}/bin/git"))
        val engine=Engine.get(context)
        withTimeout(260_000) {
            while(!engine.status.value.ready) {
                engine.status.value.error?.let { error(it) }
                delay(1000)
            }
        }
        assertTrue(engine.usingTermux)
        val api=checkNotNull(engine.api)
        val path=JSONObject(api.call("/path"))
        println("TERMUX_PATH: $path")
        assertEquals(TermuxBridge.HOME,path.getString("home"))
        assertTrue(api.health())
        engine.stop()
    }

    @Test fun zipTransferAndNoOverwrite() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val name="archive-check-${System.currentTimeMillis()}"
        val bytes=java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("src/hello.txt")); zip.write("Привет из ZIP".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val path=TermuxArchive.importZip(context,bytes.inputStream(),name)
        try {
            assertEquals("Привет из ZIP",TermuxBridge.execute(context,"cat src/hello.txt",path).trimEnd())
            var rejected=false
            try { TermuxArchive.importZip(context,bytes.inputStream(),name) } catch(_: IllegalStateException) { rejected=true }
            assertTrue("Existing project was overwritten",rejected)
            val output=java.io.ByteArrayOutputStream()
            TermuxArchive.exportZip(context,path,output)
            val recovered=mutableMapOf<String,String>()
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while(true) { val entry=zip.nextEntry?:break; if(!entry.isDirectory) recovered[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals("Привет из ZIP",recovered["src/hello.txt"])
        } finally {
            TermuxBridge.execute(context,"rm src/hello.txt; rmdir src; cd ..; rmdir ${TermuxBridge.quote(name)}",path)
        }
    }
}
