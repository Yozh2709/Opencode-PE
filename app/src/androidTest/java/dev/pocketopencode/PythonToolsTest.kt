package dev.pocketopencode

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PythonToolsTest {
    @Test fun pythonAndPipInstallAndRunDependency() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        instrumentation.waitForIdleSync()
        val engine=Engine.get(context)
        engine.prepare()
        val fixture=File(context.cacheDir,"python-check-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val versions=engine.command(listOf("/system/bin/sh","-c","python --version && python3 --version && pip --version && pip3 --version"),fixture)
            assertTrue(versions,versions.contains("Python 3.14") && versions.contains("pip 26."))
            val python=File(engine.usr,"bin/python").absolutePath
            val smoke=engine.command(listOf(python,"-c","import ssl, sqlite3, ctypes, bz2, lzma, zlib, socket, subprocess, sys; assert sqlite3.connect(':memory:').execute('select 42').fetchone()[0] == 42; assert subprocess.check_output([sys.executable, '-c', 'print(42)']).strip() == b'42'; print('stdlib and subprocess OK')"),fixture)
            assertTrue(smoke,smoke.contains("stdlib and subprocess OK"))
            engine.command(listOf(File(engine.usr,"bin/pip").absolutePath,"install","--disable-pip-version-check","--no-cache-dir","--target",File(fixture,"packages").absolutePath,"packaging==25.0"),fixture)
            val check=engine.command(listOf(python,"-c","import sys; sys.path.insert(0, 'packages'); from packaging.version import Version; assert Version('2.0') > Version('1.9'); print('pip dependency OK')"),fixture)
            assertTrue(check,check.contains("pip dependency OK"))
        } finally { fixture.deleteRecursively() }
    }
}
