package dev.pocketopencode

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The only entry into the other application's sandbox is its public, permission-gated API. */
object TermuxBridge {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val SETUP = "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings"
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Bundle>>()
    fun installed(context: Context) = try { context.packageManager.getPackageInfo("com.termux", 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    fun permitted(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun supported(context: Context) = try {
        val service=context.packageManager.getServiceInfo(ComponentName("com.termux","com.termux.app.RunCommandService"),0)
        service.exported && service.permission == PERMISSION
    } catch(_: PackageManager.NameNotFoundException) { false }
    fun preferred(context: Context): Boolean = when(context.getSharedPreferences("engine",0).getString("mode","auto")) {
        "embedded" -> false
        "termux" -> true
        else -> installed(context)
    }
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    fun complete(id: String?, result: Bundle?) {
        if(id == null) return
        waiting.remove(id)?.let { if(result == null) it.completeExceptionally(IllegalStateException(tr(UiText.TermuxEmptyResult))) else it.complete(result) }
    }
    suspend fun execute(context: Context, command: String, directory: String = HOME, timeoutMs: Long = 120_000): String {
        check(installed(context)) { tr(UiText.TermuxMissing) }
        check(supported(context)) { tr(UiText.TermuxUnsupportedBridge) }
        check(permitted(context)) { tr(UiText.TermuxPermissionHint) }
        require(command.toByteArray().size < 90_000) { tr(UiText.TermuxCommandTooLarge) }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<Bundle>()
        waiting[id] = result
        val reply = Intent(context,TermuxResultReceiver::class.java).setData(Uri.parse("pocket-termux://result/$id"))
        val flags = PendingIntent.FLAG_ONE_SHOT or (if(Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context,0,reply,flags)
        try {
            val intent = Intent("com.termux.RUN_COMMAND").setClassName("com.termux","com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH","$PREFIX/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS",arrayOf("-c",command))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR",directory)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND",true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL","Opencode")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",pending)
            check(context.startService(intent) != null) { tr(UiText.TermuxServiceFailed) }
            val bundle = withTimeout(timeoutMs) { result.await() }
            val output = bundle.getString("stdout").orEmpty()
            val error = bundle.getString("stderr").orEmpty()
            check(bundle.getInt("err",-1) == -1 && bundle.getInt("exitCode",-1) == 0) {
                bundle.getString("errmsg").orEmpty().ifBlank { error.ifBlank { output.ifBlank { tr(UiText.TermuxExitCode, bundle.getInt("exitCode",-1)) } } }.takeLast(4000)
            }
            return output + error
        } catch(e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException(tr(UiText.TermuxConnectionTimeout),e)
        } finally { waiting.remove(id); pending.cancel() }
    }
}

class TermuxResultReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxBridge.complete(intent.data?.lastPathSegment,intent.getBundleExtra("result"))
    }
}
