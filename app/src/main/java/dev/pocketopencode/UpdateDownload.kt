package dev.pocketopencode

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DownloadProgress(val status: Int, val bytes: Long, val total: Long) {
    val percent: Int? get() = if(total>0) ((bytes.coerceAtLeast(0).toDouble()/total)*100).toInt().coerceIn(0,100) else null
}

internal class UpdateDownload(private val context: Context) {
    private val prefs=AppUpdates.preferences(context)
    private val manager=context.getSystemService(DownloadManager::class.java)
    val id get()=prefs.getLong("download-id",-1)
    val release: AppRelease? get() {
        val version=prefs.getString("download-version",null) ?: return null
        if((AppVersion.parse(version) ?: return null)<=requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))) return null
        return AppRelease(version,prefs.getString("download-url","").orEmpty(),prefs.getLong("download-size",0),prefs.getString("download-digest","").orEmpty())
    }
    fun start(release: AppRelease) {
        cancel()
        val request=DownloadManager.Request(Uri.parse(release.apk))
            .setTitle("Opencode ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"update-${System.currentTimeMillis()}.apk")
        val newId=manager.enqueue(request)
        prefs.edit().putLong("download-id",newId).putString("download-version",release.version)
            .putString("download-url",release.apk).putLong("download-size",release.size).putString("download-digest",release.digest).commit()
    }
    fun cancel() {
        if(id!=-1L) manager.remove(id)
        prefs.edit().remove("download-id").remove("download-version").apply()
    }
    suspend fun progress(): DownloadProgress? = withContext(Dispatchers.IO) {
        if(id==-1L) return@withContext null
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if(!cursor.moveToFirst()) return@use null
            fun number(key: String)=cursor.getLong(cursor.getColumnIndexOrThrow(key))
            DownloadProgress(number(DownloadManager.COLUMN_STATUS).toInt(),number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
    }
    suspend fun verifiedApk(): File = withContext(Dispatchers.IO) {
        val release=requireNotNull(release)
        check(progress()?.status==DownloadManager.STATUS_SUCCESSFUL)
        val file=File(context.cacheDir,"updates/${release.version}.apk")
        file.parentFile!!.mkdirs()
        try {
            manager.openDownloadedFile(id).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            check(file.length()==release.size) { "APK size mismatch" }
            if(release.digest.isNotBlank()) {
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) } }
                check("sha256:"+digest.digest().joinToString("") { "%02x".format(it) }==release.digest) { "APK checksum mismatch" }
            }
            val pm=context.packageManager
            val archive=requireNotNull(pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES))
            val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
            check(archive.packageName==context.packageName && archive.longVersionCode>installed.longVersionCode && archive.versionName==release.version) { "Unexpected APK version" }
            fun signatures(info: android.content.pm.PackageInfo)=requireNotNull(info.signingInfo).apkContentsSigners.map { it.toCharsString() }.toSet()
            check(signatures(archive)==signatures(installed)) { "APK signature mismatch" }
            file
        } catch(e: Exception) { file.delete(); throw e }
    }
}
