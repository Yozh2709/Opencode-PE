package dev.pocketopencode

import android.app.DownloadManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadTest {
    @Test fun downloadSurvivesControllerRecreationAndRejectsInvalidApk() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val download=UpdateDownload(context)
        check(download.id==-1L) { "Do not overwrite an existing user download" }
        val server=MockWebServer()
        server.start()
        try {
            val body="This is not an APK".repeat(4096)
            server.enqueue(MockResponse().setBody(body))
            val release=AppRelease("99.0.0",server.url("/update.apk").toString(),body.toByteArray().size.toLong())
            download.start(release)
            val recreated=UpdateDownload(context)
            assertEquals(release,recreated.release)
            assertEquals(download.id,recreated.id)
            withTimeout(30000) {
                while(recreated.progress()?.status!=DownloadManager.STATUS_SUCCESSFUL) {
                    assertNotEquals(DownloadManager.STATUS_FAILED,recreated.progress()?.status)
                    delay(200)
                }
            }
            assertEquals(100,recreated.progress()?.percent)
            var rejected=false
            try { recreated.verifiedApk() } catch(_: IllegalArgumentException) { rejected=true }
            assertTrue("An invalid APK must never reach the installer",rejected)
            recreated.cancel()
            assertNull(recreated.progress())
        } finally { download.cancel(); server.shutdown() }
    }
}
