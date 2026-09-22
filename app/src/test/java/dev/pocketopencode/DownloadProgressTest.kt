package dev.pocketopencode

import org.junit.Assert.*
import org.junit.Test

class DownloadProgressTest {
    @Test fun unknownLengthDoesNotPretendToBeComplete() {
        assertNull(DownloadProgress(0,100,-1).percent)
        assertNull(DownloadProgress(0,0,0).percent)
    }
    @Test fun progressHandlesLargeApksAndClampsInvalidCounts() {
        assertEquals(50,DownloadProgress(0,3_000_000_000,6_000_000_000).percent)
        assertEquals(0,DownloadProgress(0,-1,100).percent)
        assertEquals(100,DownloadProgress(0,101,100).percent)
    }
}
