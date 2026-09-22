package dev.pocketopencode

import android.content.Context
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A token-protected loopback transfer, also a lease for the Termux child process. */
class LocalTransfer(private val routes: Map<String, (OutputStream) -> Unit>, private val uploads: Map<String,(InputStream,Long)->Unit> = emptyMap()): Closeable {
    private val socket = ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    val url = "http://127.0.0.1:${socket.localPort}/$token"
    @Volatile private var current: Socket? = null
    private val thread = Thread({
        while(!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    current = client; client.soTimeout = 5000
                    val input = client.getInputStream()
                    val request = StringBuilder()
                    while(request.length < 8192 && !request.endsWith("\r\n\r\n")) {
                        val next = input.read(); if(next < 0) break; request.append(next.toChar())
                    }
                    val parts = request.lineSequence().first().trim().split(' ')
                    val path=if(parts.size==3&&parts[1].startsWith("/$token/")) parts[1].removePrefix("/$token/") else ""
                    val route = if(parts.firstOrNull()=="GET") routes[path] else null
                    val upload = if(parts.firstOrNull()=="PUT") uploads[path] else null
                    val out = client.getOutputStream().buffered()
                    if(upload!=null) {
                        val length=request.lineSequence().firstOrNull { it.startsWith("Content-Length:",true) }?.substringAfter(':')?.trim()?.toLongOrNull() ?: -1
                        require(length in 0..268435456) { tr(UiText.InvalidTransferSize) }
                        upload(input,length)
                    }
                    out.write((if(route == null&&upload==null) "HTTP/1.1 404 Not Found\r\n" else "HTTP/1.1 200 OK\r\n").toByteArray())
                    out.write("Connection: close\r\nCache-Control: no-store\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                    route?.invoke(out); out.flush()
                }
            } catch(_: Exception) { /* invalid request, client disconnected or lease closed */ }
            finally { current = null }
        }
    },"pocket-termux-transfer").apply { isDaemon = true; start() }
    override fun close() { socket.close(); current?.close() }
}

class TermuxRuntime(private val context: Context, private val port: Int, private val password: String, private val phase: (UiText)->Unit = {}): Closeable {
    companion object {
        // UI-only APK updates reuse the same payload. Bump this when core/Bun assets change.
        val ROOT = "${TermuxBridge.HOME}/.local/share/pocket-opencode/runtime-1.18.31-2"
    }
    private var transfer: LocalTransfer? = null
    suspend fun run(): String {
        // Check the public bridge before preparing any private runtime files.
        val packages=mapOf("curl" to "curl","unzip" to "unzip","zip" to "zip","git" to "git","rg" to "ripgrep","node" to "nodejs")
        val missing=TermuxBridge.execute(context,packages.keys.joinToString("; ") { "$it ${if(it in listOf("zip","unzip")) "-v" else "--version"} >/dev/null 2>&1 || printf '$it\\n'" }+"; true",timeoutMs=20_000)
            .lineSequence().filter { it.isNotBlank() }.map { packages[it] ?: error(tr(UiText.UnexpectedTermuxResponse, it)) }.toList()
        if(missing.isNotEmpty()) {
            phase(UiText.InstallingTermuxTools)
            TermuxBridge.execute(context,"export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y -o Dpkg::Options::=--force-confold openssl "+missing.joinToString(" ",transform=TermuxBridge::quote),timeoutMs=900_000)
            TermuxBridge.execute(context,"set -e; "+packages.keys.joinToString("; ") { "$it ${if(it in listOf("zip","unzip")) "-v" else "--version"} >/dev/null" },timeoutMs=20_000)
        }
        phase(UiText.StartingTermux)
        val server = LocalTransfer(mapOf(
            "bundle.zip" to { out -> bundle(out) },
            "lease" to { out -> out.write("alive".toByteArray()) },
            "launch.sh" to { out -> out.write(launcher().toByteArray()) }
        ))
        transfer = server
        try {
            val q = TermuxBridge::quote
            val script = """
                set -eu
                umask 077
                base=${q(ROOT)}
                stage=''
                trap 'if [ -n "${'$'}stage" ]; then rm -rf -- "${'$'}stage"; fi' EXIT
                mkdir -p "${'$'}(dirname "${'$'}base")"
                if [ ! -f "${'$'}base/.complete" ]; then
                    stage=${'$'}(mktemp -d "${'$'}{base}.stage.XXXXXX")
                    curl --fail --silent --show-error --max-time 180 ${q(server.url + "/bundle.zip")} -o "${'$'}stage/payload.zip"
                    unzip -q "${'$'}stage/payload.zip" -d "${'$'}stage/files"
                    chmod 700 "${'$'}stage/files/bun"
                    touch "${'$'}stage/files/.complete"
                    if [ ! -e "${'$'}base" ]; then mv "${'$'}stage/files" "${'$'}base"; fi
                    rm -f "${'$'}stage/payload.zip"
                    rmdir "${'$'}stage" 2>/dev/null || true
                    stage=''
                fi
                script=${'$'}(mktemp "${'$'}{base}/launch.XXXXXX")
                curl --fail --silent --show-error --max-time 15 ${q(server.url + "/launch.sh")} -o "${'$'}script"
                bash "${'$'}script"
            """.trimIndent()
            return TermuxBridge.execute(context,script,timeoutMs=Long.MAX_VALUE)
        } finally { close() }
    }
    private fun launcher(): String {
        val q = TermuxBridge::quote
        return """
            set -eu
            umask 077
            rm -f -- "${'$'}0"
            export HOME=${q(TermuxBridge.HOME)} PREFIX=${q(TermuxBridge.PREFIX)}
            export PATH="${'$'}PREFIX/bin:/system/bin" SHELL="${'$'}PREFIX/bin/bash"
            export LD_LIBRARY_PATH=${q(ROOT)}:"${'$'}PREFIX/lib"
            export LD_PRELOAD=${q("$ROOT/libpocket_bun_compat.so")}${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}
            export OPENCODE_SERVER_PASSWORD=${q(password)}
            export OPENCODE_BUN_PATH=${q("$ROOT/bun")} OPENTUI_LIB_PATH=${q("$ROOT/libopentui.so")}
            export OPENCODE_DISABLE_AUTOUPDATE=true OPENCODE_DISABLE_DEFAULT_PLUGINS=false
            export OPENCODE_DISABLE_FFF=true OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER=true OPENCODE_DISABLE_TUI_AUDIO=1
            cd "${'$'}HOME"
            ${q("$ROOT/bun")} --no-install ${q("$ROOT/code/src/index.js")} serve --hostname 127.0.0.1 --port $port &
            child=${'$'}!
            cleanup() { kill "${'$'}child" 2>/dev/null || true; wait "${'$'}child" 2>/dev/null || true; }
            trap cleanup EXIT HUP INT TERM
            while kill -0 "${'$'}child" 2>/dev/null; do
                if ! curl --fail --silent --max-time 5 ${q(checkNotNull(transfer).url + "/lease")} >/dev/null; then exit 0; fi
                sleep 3
            done
            wait "${'$'}child"
        """.trimIndent()
    }
    private fun bundle(output: OutputStream) {
        val zip = ZipOutputStream(output)
        fun assets(path: String, entry: String) {
            val children = context.assets.list(path).orEmpty()
            if(children.isNotEmpty()) { children.forEach { assets("$path/$it","$entry/$it") }; return }
            zip.putNextEntry(ZipEntry(entry))
            context.assets.open(path).use { input ->
                if(path.endsWith(".js")) zip.write(input.bufferedReader().readText().replace("__POCKET_CODE_ROOT__","$ROOT/code").toByteArray())
                else input.copyTo(zip)
            }
            zip.closeEntry()
        }
        assets("runtime/code","code")
        for((source,name) in listOf("libbun.so" to "bun","libpocket_bun_compat.so" to "libpocket_bun_compat.so","libopentui.so" to "libopentui.so","libc++_shared.so" to "libc++_shared.so","libtagfix.so" to "libtagfix.so")) {
            zip.putNextEntry(ZipEntry(name)); File(context.applicationInfo.nativeLibraryDir,source).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
        zip.finish()
    }
    override fun close() { transfer?.close(); transfer = null }
}
