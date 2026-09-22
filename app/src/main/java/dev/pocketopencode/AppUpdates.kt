package dev.pocketopencode

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

internal data class AppVersion(val numbers: List<Long>, val suffix: List<String>): Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (a,b) -> if(a != b) return a.compareTo(b) }
        if(suffix.isEmpty() || other.suffix.isEmpty()) return when {
            suffix.isEmpty() && other.suffix.isEmpty() -> 0
            suffix.isEmpty() -> 1
            else -> -1
        }
        suffix.zip(other.suffix).forEach { (a,b) ->
            if(a != b) {
                val an=a.toLongOrNull(); val bn=b.toLongOrNull()
                return when { an!=null && bn!=null -> an.compareTo(bn); an!=null -> -1; bn!=null -> 1; else -> a.compareTo(b) }
            }
        }
        return suffix.size.compareTo(other.suffix.size)
    }
    companion object {
        fun parse(value: String): AppVersion? {
            val match=Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*))?(?:\\+[A-Za-z0-9.-]+)?$").matchEntire(value) ?: return null
            val numbers=(1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            return AppVersion(numbers,match.groupValues[4].takeIf { it.isNotBlank() }?.split('.') ?: emptyList())
        }
    }
}

internal data class AppRelease(val version: String, val apk: String, val size: Long = 0, val digest: String = "")

internal object AppUpdates {
    private const val REPO="https://github.com/Yozh2709/Opencode-PE"
    private val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build()
    fun preferences(context: Context)=context.getSharedPreferences("app-updates",Context.MODE_PRIVATE)
    fun remember(context: Context, release: AppRelease?) {
        preferences(context).edit().putString("available", release?.version).apply()
    }
    fun available(context: Context): Boolean {
        val version=AppVersion.parse(preferences(context).getString("available", "").orEmpty()) ?: return false
        return version > requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
    }

    // /latest excludes prereleases; this app currently ships alpha releases.
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val request=Request.Builder().url("https://api.github.com/repos/Yozh2709/Opencode-PE/releases?per_page=30")
            .header("Accept","application/vnd.github+json").header("User-Agent","Opencode-PE/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
            selectRelease(response.body?.string() ?: error("Empty GitHub response"),BuildConfig.VERSION_NAME)
        }
    }
    fun selectRelease(json: String, installed: String): AppRelease? {
        val current=requireNotNull(AppVersion.parse(installed))
        val releases=JSONArray(json)
        return (0 until releases.length()).mapNotNull { index ->
            val release=releases.getJSONObject(index)
            if(release.optBoolean("draft")) return@mapNotNull null
            val tag=release.optString("tag_name"); val version=AppVersion.parse(tag) ?: return@mapNotNull null
            if(version<=current) return@mapNotNull null
            val assets=release.optJSONArray("assets") ?: return@mapNotNull null
            val asset=(0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                val name=it.optString("name")
                name.startsWith("Opencode-PE-",true) && name.endsWith("-arm64.apk") &&
                    it.optString("state")=="uploaded" && it.optLong("size")>0 &&
                    it.optString("browser_download_url").startsWith("$REPO/releases/download/$tag/")
            } ?: return@mapNotNull null
            version to AppRelease(tag.removePrefix("v"),asset.getString("browser_download_url"),asset.getLong("size"),asset.optString("digest").takeIf { it.startsWith("sha256:") }.orEmpty())
        }.maxByOrNull { it.first }?.second
    }
    fun download(context: Context, release: AppRelease) {
        context.startActivity(Intent(context,UpdatesActivity::class.java))
    }
    suspend fun automatic(activity: ComponentActivity) {
        val prefs=preferences(activity); val now=System.currentTimeMillis()
        val elapsed=now-prefs.getLong("last-check",0)
        if(!prefs.getBoolean("automatic",true) || elapsed in 0 until TimeUnit.DAYS.toMillis(1)) return
        prefs.edit().putLong("last-check",now).apply()
        val release=try { check() } catch(e: CancellationException) { throw e } catch(_: Exception) { return }
        remember(activity,release)
        if(release==null) return
        if(activity.isFinishing || activity.isDestroyed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if(prefs.getString("notified",null)==release.version) return
        prefs.edit().putString("notified",release.version).apply()
        AlertDialog.Builder(activity).setTitle(tr(UiText.UpdateAvailable,release.version))
            .setMessage(tr(UiText.UpdateInstallHelp))
            .setPositiveButton(tr(UiText.DownloadApk)) { _,_ -> download(activity,release) }
            .setNegativeButton(tr(UiText.UpdateLater),null).show()
    }
}
