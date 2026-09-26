package dev.pocketopencode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

class EnvironmentActivity: ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var phoneStatus: TextView
    private var renderedLanguage=""
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) restart() else {
            Toast.makeText(this,tr(UiText.PermissionSettingsHint),Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderedLanguage=nativeLanguage(NativeLanguage.locale.value)
        val content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,48,24,36); setBackgroundColor(Color.rgb(24,24,24)) }
        content.setOnApplyWindowInsetsListener { v,i -> v.setPadding(24,i.systemWindowInsetTop+24,24,i.systemWindowInsetBottom+24); i.consumeSystemWindowInsets() }
        fun label(text: String) = TextView(this).apply { this.text=text; setTextColor(Color.LTGRAY); textSize=16f; setPadding(0,16,0,16) }.also { content.addView(it) }
        fun button(text: String, click: () -> Unit) { content.addView(Button(this).apply { this.text=text; setOnClickListener { click() } }) }
        label(tr(UiText.EnvironmentTitle)).textSize=24f
        button(tr(UiText.Updates)) { startActivity(Intent(this,UpdatesActivity::class.java)) }
        label(tr(UiText.PhoneControlTitle)).textSize=20f
        phoneStatus=label("")
        button(tr(UiText.PhoneControlSettings)) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        label(tr(UiText.PhoneControlHelp)).textSize=13f
        label(tr(UiText.LanguageHelp))
        status=label("")
        val engine = Engine.get(this)
        fun diagnostics(): String = buildString {
            appendLine("Opencode ${BuildConfig.VERSION_NAME}")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Kernel: ${System.getProperty("os.version")}")
            appendLine("Backend: ${if(engine.status.value.termux) "Termux" else "Embedded"}")
            appendLine(engine.status.value.error ?: engine.status.value.phase)
            append(engine.logs.value)
        }
        button(tr(UiText.CopyDiagnostics)) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenCode diagnostics",diagnostics()))
            Toast.makeText(this,tr(UiText.DiagnosticsCopied),Toast.LENGTH_SHORT).show()
        }
        val logPanel = label("").apply { setTextIsSelectable(true); textSize=12f; typeface=android.graphics.Typeface.MONOSPACE }
        content.removeView(logPanel)
        content.addView(ScrollView(this).apply { addView(logPanel) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(180 * resources.displayMetrics.density).toInt()))
        lifecycleScope.launch {
            combine(engine.status, engine.logs) { _, _ -> diagnostics() }.collect { logPanel.text = it }
        }
        label(tr(UiText.AutomaticHelp))
        val prefs=getSharedPreferences("engine",0)
        val modes=RadioGroup(this)
        val modeIds=List(3) { android.view.View.generateViewId() }
        listOf("auto" to tr(UiText.AutomaticMode),"termux" to tr(UiText.TermuxOnly),"embedded" to tr(UiText.EmbeddedMode)).forEachIndexed { index,(mode,title) ->
            modes.addView(RadioButton(this).apply { id=modeIds[index]; text=title; setTextColor(Color.WHITE); isChecked=prefs.getString("mode","auto")==mode
                setOnClickListener { prefs.edit().putString("mode",mode).apply(); restart() }
            })
        }
        content.addView(modes)
        label(tr(UiText.TermuxFirstConnection))
        if(TermuxBridge.installed(this)&&!TermuxBridge.supported(this)) label(tr(UiText.TermuxUnsupportedBackup))
        button(tr(UiText.TermuxReleases)) { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/termux/termux-app/releases"))) }
        label(tr(UiText.TermuxSteps))
        label(tr(UiText.TermuxPackagesHelp))
        button(tr(UiText.CopySetup)) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(tr(UiText.TermuxSetup),TermuxBridge.SETUP))
            Toast.makeText(this,tr(UiText.CommandCopied),Toast.LENGTH_SHORT).show()
        }
        button(tr(UiText.OpenTermux)) {
            val intent=packageManager.getLaunchIntentForPackage("com.termux")
            if(intent!=null) startActivity(intent) else Toast.makeText(this,tr(UiText.TermuxMissing),Toast.LENGTH_SHORT).show()
        }
        button(tr(UiText.AllowConnect)) {
            if(!TermuxBridge.installed(this)) Toast.makeText(this,tr(UiText.InstallTermuxFirst),Toast.LENGTH_SHORT).show()
            else if(!TermuxBridge.supported(this)) Toast.makeText(this,tr(UiText.TermuxUnsupported),Toast.LENGTH_LONG).show()
            else {
                prefs.edit().putString("mode","auto").apply()
                modes.check(modeIds[0])
                if(TermuxBridge.permitted(this)) restart() else permission.launch(TermuxBridge.PERMISSION)
            }
        }
        label(tr(UiText.SeparateProjectsHelp))
        button(tr(UiText.BackToOpenCode)) { finish() }
        setContentView(ScrollView(this).apply { addView(content) })
        lifecycleScope.launch { combine(Engine.get(this@EnvironmentActivity).status, NativeLanguage.locale) { state, _ -> state }.collect {
            status.text=(if(TermuxBridge.installed(this@EnvironmentActivity)) tr(UiText.TermuxInstalledLine) else tr(UiText.TermuxMissingLine))+(it.error?:it.phase)
        } }
    }
    override fun onResume() {
        super.onResume()
        if(renderedLanguage!=nativeLanguage(NativeLanguage.locale.value)) recreate()
        phoneStatus.text=tr(if(PhoneControlService.enabled(this)) UiText.PhoneControlOn else UiText.PhoneControlOff)
    }
    private fun restart() { Engine.get(this).stop(); startForegroundService(Intent(this,EngineService::class.java)) }
}
