package dev.pocketopencode

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class UpdatesActivity: ComponentActivity() {
    private lateinit var downloads: UpdateDownload
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var cancel: Button
    private lateinit var check: Button
    private lateinit var progress: ProgressBar
    private var release: AppRelease?=null
    private var busy=false
    private val installPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(packageManager.canRequestPackageInstalls()) install()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads=UpdateDownload(this)
        release=downloads.release
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,48,24,36); setBackgroundColor(Color.rgb(24,24,24)) }
        content.setOnApplyWindowInsetsListener { v,i -> v.setPadding(24,i.systemWindowInsetTop+24,24,i.systemWindowInsetBottom+24); i.consumeSystemWindowInsets() }
        fun label(text: String)=TextView(this).apply { this.text=text; setTextColor(Color.LTGRAY); textSize=16f; setPadding(0,16,0,16) }.also(content::addView)
        label(tr(UiText.Updates)).textSize=24f
        label("Opencode ${BuildConfig.VERSION_NAME}")
        val prefs=AppUpdates.preferences(this)
        content.addView(Switch(this).apply {
            text=tr(UiText.AutomaticUpdates); setTextColor(Color.LTGRAY); isChecked=prefs.getBoolean("automatic",true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("automatic",checked).apply() }
        })
        status=label("")
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; visibility=View.GONE }
        content.addView(progress)
        action=Button(this).apply { visibility=View.GONE }
        cancel=Button(this).apply { text=tr(UiText.CancelUpdate); visibility=View.GONE; setOnClickListener { downloads.cancel(); render(null) } }
        check=Button(this).apply { text=tr(UiText.CheckUpdates); setOnClickListener { refresh() } }
        content.addView(action); content.addView(cancel); content.addView(check)
        content.addView(Button(this).apply { text=tr(UiText.BackToOpenCode); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(content) })
        if(release==null) { if(downloads.id!=-1L) downloads.cancel(); refresh() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(isActive) {
                    if(!busy && downloads.id!=-1L) {
                        try { render(downloads.progress()) }
                        catch(e: CancellationException) { throw e }
                        catch(_: Exception) { status.text=tr(UiText.UpdateDownloadFailed) }
                    }
                    delay(500)
                }
            }
        }
    }
    private fun render(state: DownloadProgress?) {
        check.isEnabled=downloads.id==-1L
        progress.visibility=if(state!=null) View.VISIBLE else View.GONE
        progress.isIndeterminate=state?.percent==null
        progress.progress=state?.percent ?: 0
        cancel.visibility=if(downloads.id!=-1L) View.VISIBLE else View.GONE
        action.visibility=if(release!=null) View.VISIBLE else View.GONE
        action.isEnabled=true
        action.text=tr(UiText.DownloadApk)
        action.setOnClickListener {
            try { downloads.start(requireNotNull(release)); action.isEnabled=false; check.isEnabled=false }
            catch(_: Exception) { status.text=tr(UiText.UpdateDownloadFailed) }
        }
        when(state?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                status.text=tr(UiText.UpdateReady); action.text=tr(UiText.InstallUpdate); action.setOnClickListener { install() }
            }
            DownloadManager.STATUS_FAILED -> { status.text=tr(UiText.UpdateDownloadFailed); action.text=tr(UiText.RetryUpdate) }
            DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PENDING,DownloadManager.STATUS_PAUSED -> {
                val amount=String.format(Locale.ROOT,"%.1f / %.1f MB",state.bytes.coerceAtLeast(0)/1048576.0,(state.total.takeIf { it>0 } ?: release?.size ?: 0)/1048576.0)
                status.text=(state.percent?.let { "$it% · $amount" } ?: amount)+if(state.status!=DownloadManager.STATUS_RUNNING) "\n"+tr(UiText.UpdateWaiting) else ""
                action.visibility=View.GONE
            }
            else -> status.text=release?.let { tr(UiText.UpdateAvailable,it.version)+"\n\n"+tr(UiText.UpdateInstallHelp) } ?: tr(UiText.UpToDate)
        }
    }
    private fun refresh() {
        busy=true; check.isEnabled=false; status.text=tr(UiText.CheckingUpdates)
        lifecycleScope.launch {
            try { release=AppUpdates.check(); AppUpdates.remember(this@UpdatesActivity,release); render(null) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { status.text=tr(UiText.UpdateCheckFailed) }
            finally { busy=false; check.isEnabled=true }
        }
    }
    private fun install() {
        if(busy) return
        if(!packageManager.canRequestPackageInstalls()) {
            try { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))) }
            catch(_: Exception) { status.text=tr(UiText.UpdateInstallFailed) }
            return
        }
        busy=true; action.isEnabled=false; cancel.isEnabled=false; status.text=tr(UiText.VerifyingUpdate)
        lifecycleScope.launch {
            try {
                val apk=downloads.verifiedApk()
                val uri=FileProvider.getUriForFile(this@UpdatesActivity,"$packageName.updates",apk)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { downloads.cancel(); status.text=tr(UiText.UpdateInstallFailed); action.text=tr(UiText.RetryUpdate); action.setOnClickListener { render(null); action.performClick() }; check.isEnabled=true; cancel.visibility=View.GONE }
            finally { busy=false; action.isEnabled=true; cancel.isEnabled=true }
        }
    }
}
