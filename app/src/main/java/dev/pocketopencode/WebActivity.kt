package dev.pocketopencode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** The upstream OpenCode web app, bundled offline; API requests go to the embedded engine. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var origin = ""
    private var loaded = ""
    private lateinit var newChat: Button
    private var creatingChat = false
    private var needsResumeSync = false
    private var permissionReply: ((String, String) -> Unit)? = null
    private var upload: ValueCallback<Array<Uri>>? = null
    private val notification = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        upload?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(it.resultCode,it.data)); upload=null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { delay(3000); AppUpdates.automatic(this@MainActivity) }
        val engine=Engine.get(this)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(24,24,24)) }
        layout.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        val bar=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(12.dp(),0,8.dp(),0) }
        val status=TextView(this).apply { text=tr(UiText.Starting); setTextColor(Color.LTGRAY); textSize=11f; gravity=android.view.Gravity.CENTER_VERTICAL; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        bar.addView(status,LinearLayout.LayoutParams(0,44.dp(),1f))
        status.setOnClickListener { openNative(EnvironmentActivity::class.java) }
        fun flatButton(label:String)=Button(this).apply {
            text=label; isAllCaps=false; textSize=13f; setTextColor(Color.rgb(230,230,230))
            setPadding(8.dp(),0,8.dp(),0); minWidth=0; minimumWidth=0
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22444444),null,android.graphics.drawable.ColorDrawable(Color.WHITE))
        }
        newChat=flatButton(tr(UiText.NewChat)).apply { tag="new-chat"; contentDescription=tr(UiText.NewChat); isEnabled=false; setOnClickListener { createNewChat() } }
        bar.addView(newChat,LinearLayout.LayoutParams(-2,44.dp()))
        val settingsButton=ImageButton(this).apply {
            setImageResource(R.drawable.ic_opencode_settings); setPadding(11.dp(),11.dp(),11.dp(),11.dp())
            scaleType=ImageView.ScaleType.FIT_CENTER; tag="settings"; contentDescription="Settings"
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22444444),null,android.graphics.drawable.ColorDrawable(Color.WHITE))
            setOnClickListener { web.evaluateJavascript("window.pocketMobile?.openSettings()",null) }
        }
        bar.addView(settingsButton,LinearLayout.LayoutParams(44.dp(),44.dp()))
        val updateButton=ImageButton(this).apply {
            setImageResource(R.drawable.ic_update); setPadding(11.dp(),11.dp(),11.dp(),11.dp())
            contentDescription=tr(UiText.Updates); visibility=View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openNative(UpdatesActivity::class.java) }
        }
        bar.addView(updateButton,LinearLayout.LayoutParams(44.dp(),44.dp()))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(isActive) { updateButton.visibility=if(AppUpdates.available(this@MainActivity)) View.VISIBLE else View.GONE; delay(1000) }
            }
        }
        layout.addView(bar)
        web=WebView(this).apply {
            setBackgroundColor(Color.rgb(24,24,24))
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webViewClient=object:WebViewClient() {
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val url=request.url
                    if("${url.scheme}://${url.authority}"!=origin || request.method!="GET")return null
                    val path=url.path.orEmpty().removePrefix("/")
                    if(path.split('/').any { it==".." })return emptyResponse()
                    val asset=if(request.isForMainFrame)"index.html" else path
                    return try {
                        val stream=if(asset=="index.html") {
                            val html=assets.open("web/$asset").bufferedReader().use { it.readText() }
                            val mobileCss=assets.open("pocket-mobile.css").bufferedReader().use { it.readText() }
                            val mobileJs=assets.open("pocket-mobile.js").bufferedReader().use { it.readText() }
                            val adapted=html.replace("</head>", "<style>$mobileCss</style><script>$mobileJs</script></head>")
                            ByteArrayInputStream(LanguagePreferences.seedHtml(adapted).toByteArray(Charsets.UTF_8))
                        } else assets.open("web/$asset")
                        val mime=when(asset.substringAfterLast('.')) {
                            "html"->"text/html";"js","mjs"->"application/javascript";"css"->"text/css"
                            "json","webmanifest"->"application/json";"svg"->"image/svg+xml";"wasm"->"application/wasm"
                            "woff2"->"font/woff2";"woff"->"font/woff";"ttf"->"font/ttf"
                            else->MimeTypeMap.getSingleton().getMimeTypeFromExtension(asset.substringAfterLast('.'))?:"application/octet-stream"
                        }
                        WebResourceResponse(mime,"UTF-8",200,"OK",mapOf("Cache-Control" to "no-cache","X-Content-Type-Options" to "nosniff"),stream)
                    }catch(_:java.io.IOException){null}
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val uri=request.url
                    if(request.isForMainFrame && "${uri.scheme}://${uri.authority}"==origin && uri.path?.startsWith("/__pocket/")==true) {
                        when(uri.path) {
                            "/__pocket/runtime" -> openNative(EnvironmentActivity::class.java)
                            "/__pocket/android" -> openNative(ToolsActivity::class.java)
                            "/__pocket/updates" -> openNative(UpdatesActivity::class.java)
                            "/__pocket/permission" -> permissionReply?.invoke(uri.getQueryParameter("id").orEmpty(),uri.getQueryParameter("reply").orEmpty())
                        }
                        return true
                    }
                    if("${uri.scheme}://${uri.authority}"==origin)return false
                    if(uri.scheme in listOf("http","https","mailto"))runCatching { startActivity(Intent(Intent.ACTION_VIEW,uri)) }
                    return true
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                    if(request.isForMainFrame)status.text=tr(UiText.WebError, error.description)
                }
            }
            webChromeClient=object:WebChromeClient() {
                override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean {
                    upload?.onReceiveValue(null);upload=callback
                    return try{filePicker.launch(params.createIntent());true}catch(_:Exception){upload=null;callback.onReceiveValue(null);false}
                }
                override fun onConsoleMessage(message:ConsoleMessage):Boolean {
                    if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.e("PocketWeb",message.message().take(500))
                    return true
                }
            }
        }
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        layout.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(layout)
        fun clearPermission() { permissionReply=null; web.evaluateJavascript("window.pocketMobile?.renderPermission(null)",null) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    var cachedApi:OpenCodeApi?=null
                    var cachedSession=""
                    var directory=""
                    while(isActive) {
                        try {
                            val client=engine.api
                            val session=visibleSession()
                            if(client==null || !engine.status.value.ready || session==null) {
                                clearPermission()
                            } else {
                                if(cachedApi!==client || cachedSession!=session) {
                                    clearPermission()
                                    directory=JSONObject(client.call("/session/$session")).getString("directory")
                                    cachedApi=client;cachedSession=session
                                }
                                val request=client.permissions(directory).objects().firstOrNull { it.optString("sessionID")==session }
                                if(engine.api!==client || visibleSession()!=session)clearPermission()
                                else if(request==null)clearPermission()
                                else {
                                    val requestDirectory=directory
                                    val requestId=request.getString("id")
                                    permissionReply={ id, reply ->
                                        if(id==requestId && reply in listOf("once","always","reject")) {
                                            lifecycleScope.launch {
                                                try {
                                                    if(engine.api===client && visibleSession()==session)client.permission(requestDirectory,id,reply)
                                                }
                                                catch(e:CancellationException) { throw e }
                                                catch(e:Exception) { Toast.makeText(this@MainActivity,e.message,Toast.LENGTH_LONG).show() }
                                                finally { clearPermission() }
                                            }
                                        }
                                    }
                                    web.evaluateJavascript("window.pocketMobile?.renderPermission(${request})",null)
                                }
                            }
                        } catch(e:CancellationException) { throw e }
                        catch(_:Exception) { clearPermission() }
                        delay(1200)
                    }
                } finally { clearPermission() }
            }
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(web.canGoBack())web.goBack() else finish()}})
        lifecycleScope.launch {
            combine(engine.status, NativeLanguage.locale) { state, _ -> state }.collect { state ->
                status.text=state.error?:state.phase
                newChat.text=tr(UiText.NewChat)
                newChat.contentDescription=tr(UiText.NewChat)
                newChat.tooltipText=tr(UiText.NewChatTooltip)
                settingsButton.contentDescription=if(NativeLanguage.locale.value=="ru") "Настройки" else "Settings"
                newChat.isEnabled=state.ready&&!creatingChat
                val api=engine.api
                if(state.ready && api!=null && loaded!=api.webUrl){
                    origin=api.webOrigin;loaded=api.webUrl
                    val requested=intent.getStringExtra("project")
                    val project=if(state.termux && requested?.startsWith(filesDir.absolutePath)==true) null else requested
                    openProject(api,project)
                }
                if(!state.ready && loaded.isNotBlank()) { loaded=""; origin=""; web.loadUrl("about:blank") }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while(isActive) { LanguagePreferences.readFromWeb(origin); delay(500) }
            }
        }
        if(Build.VERSION.SDK_INT>=33)notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        startForegroundService(Intent(this,EngineService::class.java))
    }
    private fun openNative(activity: Class<out ComponentActivity>) {
        LanguagePreferences.readFromWeb(origin)
        startActivity(Intent(this,activity))
    }
    private fun Int.dp()=(this*resources.displayMetrics.density).toInt()
    private suspend fun visibleSession():String? = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript("location.origin === ${JSONObject.quote(origin)} ? location.pathname : ''") { raw ->
            if(continuation.isActive) {
                val path=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                continuation.resume(Regex("/session/(ses_[A-Za-z0-9]+)(?:/|$)").find(path)?.groupValues?.get(1))
            }
        }
    }
    private fun createNewChat() {
        LanguagePreferences.readFromWeb(origin)
        val engine=Engine.get(this)
        val api=engine.api?:return
        if(creatingChat)return
        creatingChat=true; newChat.isEnabled=false
        // Read the SPA's actual route, not the possibly stale Android launch Intent.
        web.evaluateJavascript("location.href") { raw ->
            lifecycleScope.launch {
                try {
                    if(engine.api!==api)return@launch
                    val href=org.json.JSONTokener(raw).nextValue() as? String
                    val uri=href?.let(Uri::parse)
                    val parts=uri?.pathSegments.orEmpty()
                    val currentSession=visibleSession()
                    val directory=if(currentSession!=null) JSONObject(api.call("/session/$currentSession")).getString("directory") else if(uri!=null && "${uri.scheme}://${uri.authority}"==api.webOrigin && parts.size>=2 && parts[1]=="session") {
                        runCatching { android.util.Base64.decode(parts[0],android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).toString(Charsets.UTF_8) }.getOrNull()?.takeIf { it.startsWith('/')&&!it.contains('\u0000') }
                    } else null
                    if(directory==null) {
                        android.app.AlertDialog.Builder(this@MainActivity).setTitle(tr(UiText.ChooseProject))
                            .setMessage(tr(UiText.ChooseProjectHelp))
                            .setPositiveButton(tr(UiText.Projects)) { _,_ -> startActivity(Intent(this@MainActivity,ToolsActivity::class.java)) }
                            .setNegativeButton(tr(UiText.Cancel),null).show()
                        return@launch
                    }
                    val id=api.create(directory)
                    if(engine.api!==api)return@launch
                    intent.putExtra("project",directory)
                    val slug=android.util.Base64.encodeToString(directory.toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    web.loadUrl(api.webUrl.replace("/?auth_token=","/$slug/session/$id?auth_token="))
                } catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){Toast.makeText(this@MainActivity,tr(UiText.NewChatFailed, e.message),Toast.LENGTH_LONG).show()}
                finally { creatingChat=false; newChat.isEnabled=engine.status.value.ready }
            }
        }
    }
    private fun openProject(api:OpenCodeApi,project:String?) {
        lifecycleScope.launch {
            val engine=Engine.get(this@MainActivity)
            if((engine.usingTermux && project?.startsWith(filesDir.absolutePath)==true) || (!engine.usingTermux && project?.startsWith("/data/data/com.termux/")==true)) {
                web.loadUrl(api.webUrl); return@launch
            }
            val target=try {
                if(project.isNullOrBlank())api.webUrl else {
                    val id=api.sessions(project).objects().firstOrNull()?.getString("id")?:api.create(project)
                    val encoded=android.util.Base64.encodeToString(project.toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    api.webUrl.replace("/?auth_token=","/$encoded/session/$id?auth_token=")
                }
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){Toast.makeText(this@MainActivity,tr(UiText.OpenProjectFailed, e.message),Toast.LENGTH_LONG).show();api.webUrl}
            if(origin==api.webOrigin)web.loadUrl(target)
        }
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        needsResumeSync=false
        Engine.get(this).api?.let { origin=it.webOrigin;loaded=it.webUrl;openProject(it,intent.getStringExtra("project")) }
    }
    override fun onResume() {
        super.onResume()
        val engine=Engine.get(this)
        if(engine.status.value.ready && engine.usingTermux!=TermuxBridge.preferred(this)) {
            needsResumeSync=false
            startForegroundService(Intent(this,EngineService::class.java))
            return
        }
        if(!needsResumeSync)return
        needsResumeSync=false
        val api=engine.api?:return
        if(!engine.status.value.ready || origin!=api.webOrigin)return
        // Android backgrounding does not provide the browser's bfcache lifecycle.
        // Reconnecting SSE alone cannot recover missed message/status events.
        // Recreate the current web view page to fetch authoritative session state;
        // the core keeps running and the upstream persisted draft store is retained.
        web.evaluateJavascript("location.href") { raw ->
            if(isFinishing || isDestroyed || engine.api!==api || origin!=api.webOrigin ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return@evaluateJavascript
            val href=runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()?:return@evaluateJavascript
            val current=Uri.parse(href)
            if("${current.scheme}://${current.authority}"!=api.webOrigin)return@evaluateJavascript
            val refreshed=current.buildUpon().clearQuery().apply {
                for(name in current.queryParameterNames)if(name!="auth_token") {
                    current.getQueryParameters(name).forEach { appendQueryParameter(name,it) }
                }
                // Startup credentials are intentionally not persisted by upstream.
                appendQueryParameter("auth_token",Uri.parse(api.webUrl).getQueryParameter("auth_token"))
            }.build()
            permissionReply=null
            web.loadUrl(refreshed.toString())
        }
    }
    override fun onStop() {
        needsResumeSync=loaded.isNotBlank()
        super.onStop()
    }
    override fun onPause() {
        LanguagePreferences.readFromWeb(origin)
        super.onPause()
    }
    private fun emptyResponse()=WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(byteArrayOf()))
    override fun onDestroy(){upload?.onReceiveValue(null);web.destroy();super.onDestroy()}
}
