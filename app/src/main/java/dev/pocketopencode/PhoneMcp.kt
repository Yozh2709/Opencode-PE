package dev.pocketopencode

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

/** Token-protected loopback MCP endpoint (Streamable HTTP with JSON responses) exposing PhoneTools to the OpenCode core. */
object PhoneMcp {
    private val versions = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    private var socket: ServerSocket? = null
    private var token = ""

    /** Starts the endpoint once per process and returns the config fragment that registers it (OPENCODE_CONFIG_CONTENT). */
    @Synchronized fun config(context: Context): String {
        val server = socket?.takeUnless { it.isClosed } ?: open(context.applicationContext)
        return JSONObject()
            .put("mcp", JSONObject().put("phone", JSONObject()
                .put("type", "remote").put("url", "http://127.0.0.1:${server.localPort}/mcp")
                .put("headers", JSONObject().put("Authorization", "Bearer $token"))
                .put("oauth", false).put("timeout", 60_000)))
            // Reading the screen is harmless; actions follow the user's permission settings.
            .put("permission", JSONObject().put("phone_screen", "allow").put("phone_screenshot", "allow").put("phone_list_apps", "allow"))
            .toString()
    }

    private fun open(app: Context): ServerSocket {
        val secrets = SecretStore(app)
        token = secrets.get("phone-mcp-token") ?: (UUID.randomUUID().toString() + UUID.randomUUID().toString()).also { secrets.put("phone-mcp-token", it) }
        val prefs = app.getSharedPreferences("engine", 0)
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = try { ServerSocket(prefs.getInt("phone-mcp-port", 0), 16, loopback) } catch (_: IOException) { ServerSocket(0, 16, loopback) }
        prefs.edit().putInt("phone-mcp-port", server.localPort).apply()
        socket = server
        Thread({
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    Thread({ client.use { serve(it) } }, "phone-mcp-client").apply { isDaemon = true; start() }
                } catch (_: IOException) { /* socket closed or client vanished */ }
            }
        }, "phone-mcp").apply { isDaemon = true; start() }
        return server
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val request = lines.first().split(' ')
            val headers = lines.drop(1).mapNotNull { line ->
                line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
            }.toMap()
            if (request.getOrNull(1)?.substringBefore('?') != "/mcp") return reply(out, 404, "")
            if (!MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray())) return reply(out, 401, "")
            // No server-initiated stream: GET and DELETE are declined as the spec allows.
            if (request.firstOrNull() != "POST") return reply(out, 405, "")
            val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 0..8_000_000 } ?: return reply(out, 411, "")
            val body = ByteArray(length).also { DataInputStream(input).readFully(it) }
            val payload = try { JSONTokener(String(body, Charsets.UTF_8)).nextValue() }
                catch (_: JSONException) { return reply(out, 400, error(JSONObject.NULL, -32700, "Parse error").toString()) }
            val answers = when (payload) {
                is JSONArray -> (0 until payload.length()).mapNotNull { (payload.opt(it) as? JSONObject)?.let(::handle) }
                is JSONObject -> listOfNotNull(handle(payload))
                else -> listOf(error(JSONObject.NULL, -32600, "Invalid request"))
            }
            if (answers.isEmpty()) reply(out, 202, "")
            else reply(out, 200, if (payload is JSONArray) JSONArray(answers).toString() else answers.first().toString())
        } catch (_: IOException) { /* client disconnected */ }
    }

    private fun handle(message: JSONObject): JSONObject? {
        // Notifications and responses carry no id and need no answer.
        if (!message.has("id") || !message.has("method")) return null
        val id = message.get("id")
        val params = message.optJSONObject("params") ?: JSONObject()
        return try {
            val result = when (message.getString("method")) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion").takeIf { it in versions } ?: versions.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "opencode-phone").put("version", BuildConfig.VERSION_NAME))
                    .put("instructions", PhoneTools.INSTRUCTIONS)
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", PhoneTools.definitions())
                "tools/call" -> try {
                    runBlocking(Dispatchers.Default) {
                        withTimeout(50_000) { PhoneTools.call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()) }
                    }
                } catch (_: TimeoutCancellationException) { PhoneTools.failure("The phone did not finish the action within 50 seconds.") }
                else -> return error(id, -32601, "Method not found")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (e: Exception) { error(id, -32603, e.message ?: "Internal error") }
    }

    private fun error(id: Any, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < 16_384) {
            val next = input.read()
            if (next < 0) return null
            bytes.write(next)
            matched = when {
                next == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                next == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                next == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return bytes.toString("ISO-8859-1").trimEnd()
        }
        return null
    }

    private fun reply(out: OutputStream, code: Int, body: String) {
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Length Required" }
        val data = body.toByteArray(Charsets.UTF_8)
        val allow = if (code == 405) "Allow: POST\r\n" else ""
        out.write("HTTP/1.1 $code $reason\r\n${allow}Content-Type: application/json\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.flush()
    }
}
