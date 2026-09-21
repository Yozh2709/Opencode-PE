package dev.pocketopencode

import android.content.Context
import android.system.Os
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EngineStatus(val phaseKey: UiText = UiText.Stopped, val ready: Boolean = false, val error: String? = null, val termux: Boolean = false) {
    val phase: String get() = tr(phaseKey)
}

class Engine private constructor(val context: Context) {
    companion object {
        @Volatile private var instance: Engine? = null
        fun get(context: Context): Engine = instance ?: synchronized(this) { instance ?: Engine(context.applicationContext).also { instance = it } }
    }
    val workspace = Workspace(File(context.filesDir, "projects"))
    val home = File(context.filesDir, "home")
    val usr = File(context.filesDir, "usr")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(EngineStatus())
    val status = mutableStatus.asStateFlow()
    val logs = MutableStateFlow("")
    private var process: Process? = null
    private var startJob: Job? = null
    private var generation = 0L
    private var termuxRuntime: TermuxRuntime? = null
    val usingTermux: Boolean get() = status.value.termux
    private val secret = SecretStore(context)
    var api: OpenCodeApi? = null; private set
    fun log(line: String) { synchronized(logs) { logs.value = (logs.value + line + "\n").takeLast(40_000) } }

    private fun copyAssets(asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                if(asset.startsWith("runtime/code/") && asset.endsWith(".js"))dest.writeText(input.bufferedReader().readText().replace("__POCKET_CODE_ROOT__",File(context.filesDir,"code").absolutePath))
                else dest.outputStream().use { input.copyTo(it) }
            }
        } else { dest.mkdirs(); children.forEach { copyAssets("$asset/$it", File(dest,it)) } }
    }
    @Synchronized fun prepare() {
        home.mkdirs(); usr.mkdirs(); File(home,"tmp").mkdirs()
        val marker = File(usr,".runtime-${BuildConfig.VERSION_CODE}")
        if (!marker.exists()) { copyAssets("runtime/usr", usr); copyAssets("runtime/code",File(context.filesDir,"code")); marker.writeText("installed") }
        val libs = File(context.applicationInfo.nativeLibraryDir)
        check(File(libs,"libbun.so").isFile) { tr(UiText.MissingCore) }
        val mapping = JSONObject(context.assets.open("runtime/executables.json").bufferedReader().use { it.readText() })
        mapping.keys().forEach { rel ->
            val target = File(libs,mapping.getString(rel))
            val link = File(usr,if (rel == "opencode") "bin/opencode" else rel)
            link.parentFile?.mkdirs()
            this.link(target,link)
            if (rel.startsWith("lib/") && rel.contains(".so.")) {
                val stem = rel.substringBefore(".so.") + ".so"
                val versions = rel.substringAfter(".so.").split('.')
                this.link(target,File(usr,stem))
                for (count in 1 until versions.size) this.link(target,File(usr,stem+"."+versions.take(count).joinToString(".")))
            }
        }
        // Git selects transport helpers by basename. APK packaging keeps executables under lib/.
        val git = File(usr,"bin/git")
        for (name in listOf("git-receive-pack", "git-upload-pack", "git-upload-archive")) link(git, File(usr,"bin/$name"))
        val remote = File(usr,"libexec/git-core/git-remote-http")
        if (remote.exists()) for (name in listOf("git-remote-https","git-remote-ftp","git-remote-ftps")) link(remote,File(usr,"libexec/git-core/$name"))
        link(File("/system/bin/sh"), File(usr,"bin/sh"))
        for (name in listOf("opencode", "npm", "npx")) {
            link(File(libs,"libpocket_$name.so"), File(usr,"bin/$name"))
        }
        for (name in listOf("pip", "pip3", "pip3.14")) link(File(libs,"libpocket_pip.so"), File(usr,"bin/$name"))
        val config = File(home,".config/opencode/opencode.json")
        if (!config.exists()) {
            config.parentFile?.mkdirs()
            config.writeText(JSONObject().put("permission",JSONObject().put("*","ask").put("read","allow").put("glob","allow").put("grep","allow").put("list","allow"))
                .put("autoupdate",false).put("share","disabled").toString(2))
        }
        val instructions = File(home,".config/opencode/AGENTS.md")
        if (!instructions.exists()) instructions.writeText("""
            This project runs inside an Android application on this device.
            Use /system/bin/sh. Git, ripgrep, Bun, Node.js, Python and pip are bundled on PATH.
            Run JavaScript with node or bun. Run npm as: node "${'$'}NPM_CLI" <args>.
            For npm installations use --ignore-scripts unless the user specifically needs package scripts.
            Android prevents executing downloaded native binaries and executable scripts in writable storage.
            Invoke scripts explicitly with node, bun, or /system/bin/sh. Native addons, downloaded language servers,
            and desktop build toolchains may be unavailable. Report actual command failures; do not claim unsupported tools work.
        """.trimIndent())
    }
    private fun link(target: File, link: File) {
        if (!target.exists()) return
        if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
            if (Os.readlink(link.absolutePath) == target.absolutePath) return
            check(link.delete()) { tr(UiText.LinkFailed, link.name) }
        }
        if (link.exists()) return
        link.parentFile?.mkdirs(); Os.symlink(target.absolutePath,link.absolutePath)
    }
    fun environment(): Map<String,String> {
        val libs = context.applicationInfo.nativeLibraryDir
        return mapOf(
            "HOME" to home.absolutePath, "PREFIX" to usr.absolutePath,
            "PATH" to "${usr.absolutePath}/bin:/system/bin:/system/xbin",
            "SHELL" to "/system/bin/sh", "TMPDIR" to File(home,"tmp").absolutePath,
            "TMP" to File(home,"tmp").absolutePath, "TEMP" to File(home,"tmp").absolutePath,
            "XDG_CONFIG_HOME" to File(home,".config").absolutePath,
            "XDG_DATA_HOME" to File(home,".local/share").absolutePath,
            "XDG_CACHE_HOME" to File(home,".cache").absolutePath,
            "XDG_STATE_HOME" to File(home,".local/state").absolutePath,
            "LD_LIBRARY_PATH" to "$libs:${usr.absolutePath}/lib",
            "OPENTUI_LIB_PATH" to "$libs/libopentui.so", "ANDROID_ROOT" to "/system",
            "TERMUX_VERSION" to "pocket-embedded", "OPENCODE_DISABLE_AUTOUPDATE" to "true",
            "OPENCODE_DISABLE_TUI_AUDIO" to "1", "OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER" to "true",
            "OPENCODE_DISABLE_FFF" to "true", "OPENCODE_DISABLE_DEFAULT_PLUGINS" to "false",
            "OPENCODE_BUN_PATH" to File(libs,"libbun.so").absolutePath,
            "POCKET_OPENCODE_ENTRY" to File(context.filesDir,"code/src/index.js").absolutePath,
            "GIT_EXEC_PATH" to File(usr,"libexec/git-core").absolutePath,
            "GIT_TEMPLATE_DIR" to File(usr,"share/git-core/templates").absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1", "GIT_TERMINAL_PROMPT" to "0", "GIT_PAGER" to "cat",
            "SSL_CERT_FILE" to File(usr,"etc/tls/cert.pem").absolutePath,
            "CURL_CA_BUNDLE" to File(usr,"etc/tls/cert.pem").absolutePath,
            "GIT_SSL_CAINFO" to File(usr,"etc/tls/cert.pem").absolutePath,
            "NODE_EXTRA_CA_CERTS" to File(usr,"etc/tls/cert.pem").absolutePath,
            "PYTHONHOME" to usr.absolutePath,
            "PYTHONPYCACHEPREFIX" to File(home,".cache/python").absolutePath,
            "PIP_CERT" to File(usr,"etc/tls/cert.pem").absolutePath,
            "PIP_CACHE_DIR" to File(home,".cache/pip").absolutePath,
            "NPM_CLI" to File(usr,"lib/node_modules/npm/bin/npm-cli.js").absolutePath,
            "npm_config_cache" to File(home,".npm").absolutePath
        )
    }
    private fun builder(command: List<String>, directory: File): ProcessBuilder = ProcessBuilder(command)
        .directory(directory).redirectErrorStream(true).apply { environment().putAll(this@Engine.environment()) }

    @Synchronized fun start() {
        if((process?.isAlive == true || status.value.ready) && usingTermux != TermuxBridge.preferred(context)) stop()
        if (startJob?.isActive == true || process?.isAlive == true) return
        val run = ++generation
        startJob = scope.launch {
            val termux = TermuxBridge.preferred(context)
            mutableStatus.value = EngineStatus(if(termux) UiText.ConnectingTermux else UiText.Preparing,termux=termux)
            try {
                if(termux) { runTermux(run); return@launch }
                prepare()
                ensureActive()
                val password = secret.get("server-password") ?: UUID.randomUUID().toString().also { secret.put("server-password",it) }
                val preferences = context.getSharedPreferences("engine",0)
                val savedPort = preferences.getInt("port",0)
                val port = try { ServerSocket(savedPort).use { it.localPort } } catch (_: java.io.IOException) { ServerSocket(0).use { it.localPort } }
                preferences.edit().putInt("port",port).apply()
                val client = OpenCodeApi("http://127.0.0.1:$port", password)
                mutableStatus.value = EngineStatus(UiText.Starting)
                val binary = File(context.applicationInfo.nativeLibraryDir,"libbun.so")
                val entry = File(context.filesDir,"code/src/index.js")
                val p = builder(listOf(binary.absolutePath,"--no-install",entry.absolutePath,"serve","--hostname","127.0.0.1","--port",port.toString()),home)
                    .apply { environment()["OPENCODE_SERVER_PASSWORD"] = password }.start()
                synchronized(this@Engine) {
                    if (generation != run) { p.destroyForcibly(); return@launch }
                    process = p
                }
                scope.launch {
                    try { p.inputStream.bufferedReader().useLines { lines -> lines.forEach(::log) } }
                    catch (e: java.io.IOException) { if (p.isAlive && process === p) log(tr(UiText.LogError, e.message)) }
                }
                var healthy = false
                repeat(90) {
                    ensureActive()
                    if (!p.isAlive) error(tr(UiText.CoreExitDetails, p.exitValue()))
                    if (!healthy) {
                        healthy = try { client.health() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                        if (!healthy) delay(1000)
                    }
                }
                check(healthy) { tr(UiText.CoreTimeout) }
                synchronized(this@Engine) {
                    if (generation != run) return@launch
                    api = client; mutableStatus.value = EngineStatus(UiText.EmbeddedStatus, true)
                }
                val exit = p.waitFor()
                synchronized(this@Engine) {
                    if (generation == run) { process = null; api = null; mutableStatus.value = EngineStatus(UiText.Stopped,error=tr(UiText.CoreExited, exit)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { synchronized(this@Engine) {
                if (generation == run) { process?.destroyForcibly(); process = null; api = null; log(e.toString()); mutableStatus.value = EngineStatus(UiText.StartFailed, error=e.message,termux=termux) }
            } }
        }
    }
    private suspend fun runTermux(run: Long) = coroutineScope {
        check(TermuxBridge.installed(context)) { tr(UiText.TermuxMissingMode) }
        check(TermuxBridge.supported(context)) { tr(UiText.TermuxUnsupportedMode) }
        check(TermuxBridge.permitted(context)) { tr(UiText.ConnectTermuxHint) }
        val password = secret.get("termux-password") ?: UUID.randomUUID().toString().also { secret.put("termux-password",it) }
        val preferences = context.getSharedPreferences("engine",0)
        val saved = preferences.getInt("termux-port",0)
        val port = try { ServerSocket(saved).use { it.localPort } } catch(_: java.io.IOException) { ServerSocket(0).use { it.localPort } }
        preferences.edit().putInt("termux-port",port).apply()
        val client = OpenCodeApi("http://127.0.0.1:$port",password)
        val runtime = TermuxRuntime(context,port,password) { phase ->
            synchronized(this@Engine) { if(generation==run) mutableStatus.value=EngineStatus(phase,termux=true) }
        }
        synchronized(this@Engine) { if(generation != run) return@coroutineScope; termuxRuntime = runtime }
        val task = async { runtime.run() }
        try {
            withTimeout(1_200_000) {
                while(true) {
                    ensureActive()
                    if(task.isCompleted) error(tr(UiText.TermuxLaunchEnded, task.await().takeLast(2000)))
                    val healthy = try { client.health() } catch(e: CancellationException) { throw e } catch(_: Exception) { false }
                    if(healthy) break
                    delay(1000)
                }
            }
            synchronized(this@Engine) {
                if(generation != run) return@coroutineScope
                api = client; mutableStatus.value = EngineStatus(UiText.TermuxStatus,true,termux=true)
            }
            log(tr(UiText.TermuxRuntimeLog, TermuxBridge.HOME))
            val result = task.await()
            error(tr(UiText.TermuxCoreExited, result.takeLast(2000)))
        } finally {
            runtime.close(); task.cancel()
            synchronized(this@Engine) { if(termuxRuntime === runtime) termuxRuntime = null }
        }
    }
    suspend fun attach(port: Int, password: String) {
        require(port in 1024..65535) { tr(UiText.PortRange) }
        check(process?.isAlive != true) { tr(UiText.StopEmbeddedFirst) }
        val client = OpenCodeApi("http://127.0.0.1:$port",password)
        check(client.health()) { tr(UiText.TermuxNotResponding) }
        api = client; mutableStatus.value = EngineStatus(UiText.TermuxStatus,true)
    }
    @Synchronized fun stop() {
        generation++
        startJob?.cancel(); startJob = null
        termuxRuntime?.close(); termuxRuntime = null
        val previous = process; process = null; api = null
        previous?.destroyForcibly()
        mutableStatus.value = EngineStatus()
    }
    suspend fun command(args: List<String>, directory: File, timeout: Long = 120): String = withContext(Dispatchers.IO) {
        check(!usingTermux && !TermuxBridge.preferred(context)) { tr(UiText.EmbeddedCommandsDisabled) }
        prepare()
        val p = builder(args,directory).start()
        val output = StringBuilder()
        val reader = async { p.inputStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(output) { output.appendLine(it); if(output.length>100_000) output.delete(0,output.length-100_000) } } } }
        try {
            check(p.waitFor(timeout,TimeUnit.SECONDS)) { tr(UiText.CommandTimeout, timeout) }
            reader.await(); check(p.exitValue()==0) { output.toString().ifBlank { tr(UiText.ExitCode, p.exitValue()) } }; output.toString()
        } finally { if(p.isAlive) p.destroyForcibly(); reader.cancel() }
    }
}
