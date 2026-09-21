package dev.pocketopencode

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun TermuxProjectsScreen(engine: EngineStatus) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("pwd; command -v git; git --version; node --version") }
    var output by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(TermuxBridge.HOME) }
    fun open(path: String) { context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("project",path)) }
    fun run(block: suspend () -> Unit) { scope.launch {
        working=true
        try { block() } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { output=e.message.orEmpty() }
        finally { working=false }
    } }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) run {
            selected=context.contentResolver.openInputStream(uri)!!.use { TermuxArchive.importZip(context,it,name) }
            output=tr(UiText.ProjectImportedTermux); open(selected)
        }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) run { context.contentResolver.openOutputStream(uri)!!.use { TermuxArchive.exportZip(context,selected,it) }; output=tr(UiText.ProjectExported) }
    }
    val embedded=remember { Engine.get(context).workspace.projects() }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(tr(UiText.TermuxProjects),style=MaterialTheme.typography.headlineSmall)
        Text(engine.error?:engine.phase)
        Text(tr(UiText.TermuxProjectHelp))
        Button(onClick={open(selected)},enabled=engine.ready) { Text(tr(UiText.OpenInOpenCode)) }
        OutlinedTextField(selected,{selected=it},label={Text(tr(UiText.TermuxProjectFolder))},modifier=Modifier.fillMaxWidth())
        HorizontalDivider()
        OutlinedTextField(name,{name=it},label={Text(tr(UiText.NewProjectName))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{url=it},label={Text(tr(UiText.OptionalGitUrl))},modifier=Modifier.fillMaxWidth())
        Button(enabled=engine.ready&&!working&&name.isNotBlank(),onClick={run {
            require(name.matches(Regex("[\\p{L}\\p{N}_ .-]{1,64}"))&&!name.startsWith('.')) { tr(UiText.InvalidProjectName) }
            val path="${TermuxBridge.HOME}/pocket-projects/$name"
            require(url.isBlank() || (url.startsWith("https://")&&!url.contains('\n'))) { tr(UiText.HttpsRepositoryRequired) }
            val q=TermuxBridge::quote
            val script="set -e; mkdir -p ${q(TermuxBridge.HOME+"/pocket-projects")}; if [ -e ${q(path)} ]; then echo ${TermuxBridge.quote(tr(UiText.ProjectExists))} >&2; exit 1; fi; " +
                if(url.isBlank()) "mkdir ${q(path)}" else "GIT_TERMINAL_PROMPT=0 git clone -- ${q(url)} ${q(path)}"
            output=TermuxBridge.execute(context,script,timeoutMs=300_000)
            selected=path; open(path)
        }}) { Text(if(url.isBlank()) tr(UiText.CreateInTermux) else tr(UiText.CloneInTermux)) }
        Button(enabled=engine.ready&&!working&&name.isNotBlank(),onClick={importer.launch(arrayOf("application/zip","application/octet-stream"))}) { Text(tr(UiText.ImportIntoTermux)) }
        Button(enabled=engine.ready&&!working&&selected!=TermuxBridge.HOME,onClick={exporter.launch(selected.substringAfterLast('/')+".zip")}) { Text(tr(UiText.ExportSelectedProject)) }
        if(embedded.isNotEmpty()) {
            var show by remember { mutableStateOf(false) }
            TextButton(onClick={show=!show}) { Text(tr(UiText.CopyEmbeddedProject)) }
            if(show) {
                Text(tr(UiText.CopyProjectHelp))
                embedded.forEach { project ->
                    TextButton(enabled=engine.ready&&!working,onClick={run {
                        val archive=java.io.File.createTempFile("pocket-copy-",".zip",context.cacheDir)
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { archive.outputStream().use { Engine.get(context).workspace.exportZip(project,it) } }
                            selected=archive.inputStream().use { TermuxArchive.importZip(context,it,project.name) }
                            open(selected)
                        } finally { archive.delete() }
                    }}) { Text(project.name) }
                }
            }
        }
        HorizontalDivider()
        OutlinedTextField(command,{command=it},label={Text(tr(UiText.TermuxCommand))},modifier=Modifier.fillMaxWidth())
        Button(enabled=engine.ready&&!working,onClick={run { output=TermuxBridge.execute(context,command,selected) }}) { Text(tr(UiText.Run)) }
        if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(output)
        TextButton(onClick={context.startActivity(Intent(context,EnvironmentActivity::class.java))}) { Text(tr(UiText.EnvironmentOldProjects)) }
    }
}
