package dev.pocketopencode

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Installs only a tiny fixture dependency; never edits the user's config/plugins. */
@RunWith(AndroidJUnit4::class)
class PackageToolsTest {
    @Test fun packagedNpmAndCommandLaunchersWork() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // realme freezes native children of a background instrumentation process.
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val engine=Engine.get(context)
        engine.prepare()
        context.assets.open("runtime/usr/lib/node_modules/npm/node_modules/@sigstore/protobuf-specs/dist/__generated__/envelope.js").use {
            assertTrue(it.read()!=-1)
        }
        val fixture=File(context.cacheDir,"package-tools-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val versions=engine.command(listOf("/system/bin/sh","-c","opencode --version && npm --version && npx --version && curl --version"),fixture)
            assertTrue(versions,versions.contains("1.18.31"))
            assertTrue(versions,versions.contains("11.19.1"))
            assertTrue(versions,versions.contains("curl 8.22.0"))
            File(fixture,"package.json").writeText("""{"name":"pocket-package-check","version":"1.0.0","private":true}""")
            engine.command(listOf(File(engine.usr,"bin/npm").absolutePath,"install","jsonc-parser@3.3.1","--ignore-scripts","--no-audit","--no-fund"),fixture)
            val parsed=engine.command(listOf(File(engine.usr,"bin/node").absolutePath,"-e","const p=require('jsonc-parser');if(p.parse('{/*ok*/\"value\":42}').value!==42)process.exit(1);console.log('npm dependency works')"),fixture)
            assertTrue(parsed,parsed.contains("npm dependency works"))
            val tls=engine.command(listOf(File(engine.usr,"bin/curl").absolutePath,"--fail","--silent","--show-error","--max-time","30","https://registry.npmjs.org/jsonc-parser/3.3.1"),fixture)
            assertTrue(tls.contains("3.3.1"))
        } finally { fixture.deleteRecursively() }
    }
}
