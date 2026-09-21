package dev.pocketopencode

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LocalTransferTest {
    @Test fun onlyExactAuthenticatedRouteCanReadPayload() {
        var reads=0
        LocalTransfer(mapOf("bundle" to { out -> reads++; out.write("private".toByteArray()) })).use { server ->
            for(url in listOf(server.url.substringBeforeLast('/')+"/wrong/bundle",server.url+"/../bundle",server.url+"/unknown")) {
                val connection=URL(url).openConnection() as HttpURLConnection
                try { assertEquals(404,connection.responseCode) } finally { connection.disconnect() }
            }
            assertEquals(0,reads)
            assertEquals("private",URL(server.url+"/bundle").readText())
            assertEquals(1,reads)
        }
    }
    @Test fun authorizedUploadPreservesBytes() {
        var bytes=byteArrayOf()
        LocalTransfer(emptyMap(),mapOf("zip" to { input,length -> bytes=input.readNBytes(length.toInt()) })).use { server ->
            val connection=URL(server.url+"/zip").openConnection() as HttpURLConnection
            try {
                connection.requestMethod="PUT"; connection.doOutput=true; connection.setFixedLengthStreamingMode(4)
                connection.outputStream.use { it.write(byteArrayOf(0,1,2,-1)) }
                assertEquals(200,connection.responseCode)
                assertArrayEquals(byteArrayOf(0,1,2,-1),bytes)
            } finally { connection.disconnect() }
        }
    }
}
