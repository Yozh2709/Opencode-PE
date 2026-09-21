package dev.pocketopencode

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Manual integration diagnostic; run only with an explicitly supplied command file. */
@RunWith(AndroidJUnit4::class)
class TermuxMcpProbeTest {
    @Test fun probe() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("runTermuxMcpProbe")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val command=context.cacheDir.resolve("termux-mcp-probe-command.txt").readText()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        instrumentation.waitForIdleSync()
        val output=try { TermuxBridge.execute(context,command,timeoutMs=600_000) } catch(e:Exception) { "PROBE ERROR: ${e.message}" }
        context.cacheDir.resolve("termux-mcp-probe-result.txt").writeText(output)
        println(output.takeLast(16000))
    }
}
