package dev.pocketopencode

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class UpdatesActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val status=label("")
        val download=Button(this).apply { text=tr(UiText.DownloadApk); visibility=View.GONE }
        val check=Button(this).apply { text=tr(UiText.CheckUpdates) }
        fun refresh() {
            check.isEnabled=false; download.visibility=View.GONE; status.text=tr(UiText.CheckingUpdates)
            lifecycleScope.launch {
                try {
                    val release=AppUpdates.check()
                    status.text=if(release==null) tr(UiText.UpToDate) else tr(UiText.UpdateAvailable,release.version)+"\n\n"+tr(UiText.UpdateInstallHelp)
                    if(release!=null) { download.visibility=View.VISIBLE; download.setOnClickListener { AppUpdates.download(this@UpdatesActivity,release) } }
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { status.text=tr(UiText.UpdateCheckFailed) }
                finally { check.isEnabled=true }
            }
        }
        check.setOnClickListener { refresh() }; content.addView(check); content.addView(download)
        content.addView(Button(this).apply { text=tr(UiText.BackToOpenCode); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(content) })
        refresh()
    }
}
