package dev.pocketopencode

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, draft: Boolean=false, state: String="uploaded", host: String="https://github.com/Yozh2709/Opencode-PE") = """
        {"tag_name":"$tag","draft":$draft,"assets":[{"name":"Opencode-PE-$tag-arm64.apk","state":"$state","size":100,"browser_download_url":"$host/releases/download/$tag/app.apk"}]}
    """.trimIndent()

    @Test fun numericAndPrereleaseOrdering() {
        fun version(s: String)=requireNotNull(AppVersion.parse(s))
        assertTrue(version("v0.4.13-alpha") > version("0.4.9-alpha"))
        assertTrue(version("0.4.13") > version("0.4.13-alpha.10"))
        assertTrue(version("0.4.13-alpha.10") > version("0.4.13-alpha.2"))
        assertTrue(version("0.4.13-alpha.beta") > version("0.4.13-alpha.10"))
        assertNull(AppVersion.parse("runtime-packages-20260922"))
    }
    @Test fun latestInstallableAlphaRegardlessOfReleaseOrder() {
        val json="[${release("v0.4.14-alpha")},${release("v0.4.20-alpha",draft=true)},${release("v0.4.16-alpha",state="starter")},${release("v0.4.15-alpha")}]"
        assertEquals("0.4.15-alpha",AppUpdates.selectRelease(json,"0.4.13-alpha")?.version)
    }
    @Test fun noDowngradeOrForeignDownload() {
        assertNull(AppUpdates.selectRelease("[${release("v0.4.13-alpha")},${release("v0.4.12-alpha")}]","0.4.13-alpha"))
        assertNull(AppUpdates.selectRelease("[${release("v0.4.14-alpha",host="https://example.com")}]","0.4.13-alpha"))
        assertNull(AppUpdates.selectRelease("[{\"tag_name\":\"v0.4.14-alpha\",\"assets\":[]}]","0.4.13-alpha"))
    }
    @Test fun carriesIntegrityMetadataFromSelectedAsset() {
        val entry=release("v0.4.14-alpha").replace("\"size\":100", "\"size\":214000000,\"digest\":\"sha256:abc123\"")
        val selected=requireNotNull(AppUpdates.selectRelease("[$entry]","0.4.13-alpha"))
        assertEquals(214000000L,selected.size)
        assertEquals("sha256:abc123",selected.digest)
    }
}
