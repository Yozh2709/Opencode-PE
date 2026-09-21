package dev.pocketopencode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only HTTPS request to a small public repository; requires Internet. */
@RunWith(AndroidJUnit4::class)
class GitCloneTest {
    @Test fun clonePublicRepositoryAndRunNpm() = runBlocking<Unit> {
        val engine=Engine.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val project=engine.workspace.create("git-check-${System.currentTimeMillis()}")
        try {
            engine.prepare()
            engine.command(listOf(File(engine.usr,"bin/git").absolutePath,"clone","--depth","1","--",
                "https://github.com/guysoft/opencode-termux.git",project.resolve("repo").absolutePath),project,90)
            assertTrue(project.resolve("repo/README.md").isFile)
            val version=engine.command(listOf(File(engine.usr,"bin/node").absolutePath,
                File(engine.usr,"lib/node_modules/npm/bin/npm-cli.js").absolutePath,"--version"),project)
            assertTrue(version,version.contains("11.19.1"))
        } finally { project.deleteRecursively() }
    }
}
