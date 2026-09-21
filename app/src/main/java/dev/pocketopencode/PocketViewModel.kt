package dev.pocketopencode

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SessionItem(val id:String,val title:String)
data class ModelItem(val provider:String,val id:String,val label:String)
data class FileItem(val path:String,val directory:Boolean)
data class UiState(
    val projects:List<String> = emptyList(), val project:String="", val session:String="",
    val sessions:List<SessionItem> = emptyList(), val messages:List<JSONObject> = emptyList(),
    val permissions:List<JSONObject> = emptyList(), val questions:List<JSONObject> = emptyList(),
    val models:List<ModelItem> = emptyList(), val providers:List<Pair<String,String>> = emptyList(),
    val provider:String="", val model:String="", val diffs:List<JSONObject> = emptyList(),
    val files:List<FileItem> = emptyList(), val folder:String="", val file:String?=null, val editor:String="",
    val error:String?=null, val notice:String?=null, val working:Boolean=false, val agentBusy:Boolean=false,
    val terminal:String="",val termux:Boolean=false,val termuxDirectory:String=""
)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

class PocketViewModel(app:Application):AndroidViewModel(app) {
    val engine=Engine.get(app)
    private val prefs=app.getSharedPreferences("ui",0)
    val state=MutableStateFlow(UiState(provider=prefs.getString("provider","").orEmpty(),model=prefs.getString("model","").orEmpty()))
    init {
        reloadProjects()
        viewModelScope.launch {
            engine.status.collect { status ->
                if(status.termux) state.update { it.copy(termux=true,project="",session="",messages=emptyList()) }
                else if(state.value.termux) { state.update { it.copy(termux=false) }; reloadProjects() }
                if(status.ready && !status.termux) action { loadProject(); loadProviders() }
            }
        }
        viewModelScope.launch {
            while(isActive) {
                delay(1800)
                if(engine.status.value.ready && state.value.project.isNotBlank()) {
                    try { refreshConversation() }
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){state.update { it.copy(error=e.message) }}
                }
            }
        }
    }
    private fun action(block:suspend ()->Unit) { viewModelScope.launch {
        state.update { it.copy(working=true,error=null,notice=null) }
        try { withContext(Dispatchers.IO) { block() } }
        catch(e:CancellationException){throw e}
        catch(e:Exception){state.update { it.copy(error=e.message?:e.toString()) }}
        finally {state.update { it.copy(working=false) }}
    } }
    fun dismissError(){state.update { it.copy(error=null,notice=null) }}
    private fun api()=checkNotNull(engine.api){tr(UiText.StartOpenCodeFirst)}
    private fun dir()=if(state.value.termux)state.value.termuxDirectory else File(engine.workspace.root,state.value.project).absolutePath
    private fun projectFile():File { check(!state.value.termux){tr(UiText.TermuxFilesPrivate)}; check(state.value.project.isNotBlank()){ tr(UiText.ChooseProject) };return File(engine.workspace.root,state.value.project) }
    private fun reloadProjects() {
        val projects=engine.workspace.projects().map { it.name }
        val chosen=state.value.project.takeIf { it in projects }?:prefs.getString("project","")?.takeIf {it in projects}?:projects.firstOrNull().orEmpty()
        state.update { it.copy(projects=projects,project=chosen) }
    }
    fun selectProject(name:String)=action {
        state.update { it.copy(project=name,session="",messages=emptyList(),file=null,folder="",diffs=emptyList(),permissions=emptyList(),questions=emptyList()) }
        prefs.edit().putString("project",name).apply();loadFiles("");if(engine.status.value.ready)loadProject()
    }
    fun createProject(name:String)=action {
        engine.workspace.create(name);reloadProjects();state.update {it.copy(project=name)};loadFiles("");if(engine.status.value.ready)loadProject()
    }
    fun importZip(uri:Uri,name:String)=action {
        val project=engine.workspace.create(name)
        try { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { engine.workspace.importZip(it,project) } }
        catch(e:Exception){project.deleteRecursively();throw e}
        reloadProjects();state.update {it.copy(project=name)};loadFiles("");if(engine.status.value.ready)loadProject()
    }
    fun exportZip(uri:Uri)=action { getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { engine.workspace.exportZip(projectFile(),it) };state.update {it.copy(notice=tr(UiText.ProjectExported))} }
    fun cloneProject(name:String,url:String)=action {
        require(url.startsWith("https://") && !url.contains('\n')){tr(UiText.UseHttpsGit)}
        val project=engine.workspace.create(name)
        try { engine.command(listOf(File(engine.usr,"bin/git").absolutePath,"clone","--",url,project.absolutePath),engine.workspace.root,300) }
        catch(e:Exception){project.deleteRecursively();throw e}
        reloadProjects();state.update {it.copy(project=name)};loadFiles("");if(engine.status.value.ready)loadProject()
    }
    private suspend fun loadProject() {
        if(state.value.project.isBlank())return
        val sessions=api().sessions(dir()).objects().map {SessionItem(it.getString("id"),it.optString("title",tr(UiText.Conversation)))}
        val saved=prefs.getString("session:${dir()}","").orEmpty()
        state.update {it.copy(sessions=sessions,session=it.session.takeIf { id->sessions.any {s->s.id==id}}?:saved.takeIf{id->sessions.any{s->s.id==id}}?:sessions.firstOrNull()?.id.orEmpty())}
        refreshConversation()
    }
    fun selectSession(id:String)=action {state.update{it.copy(session=id,messages=emptyList(),diffs=emptyList())};prefs.edit().putString("session:${dir()}",id).apply();refreshConversation()}
    fun newSession()=action {val id=api().create(dir());state.update{it.copy(session=id,messages=emptyList(),diffs=emptyList())};prefs.edit().putString("session:${dir()}",id).apply();loadProject()}
    private suspend fun refreshConversation() {
        val snapshot=state.value; val directory=dir();val client=api()
        if(snapshot.session.isBlank())return
        val messages=client.messages(directory,snapshot.session).objects()
        val permissions=client.permissions(directory).objects().filter{it.optString("sessionID")==snapshot.session}
        val questions=client.questions(directory).objects().filter{it.optString("sessionID")==snapshot.session}
        val statuses=JSONObject(client.call("/session/status",directory=directory))
        val busy=statuses.optJSONObject(snapshot.session)?.optString("type")?.let{it!="idle"}?:false
        if(state.value.session==snapshot.session && dir()==directory)state.update{it.copy(messages=messages,permissions=permissions,questions=questions,agentBusy=busy)}
    }
    fun send(text:String)=action {
        require(text.isNotBlank());require(state.value.model.isNotBlank()) {tr(UiText.ChooseProviderModel)}
        val id=state.value.session.ifBlank {api().create(dir()).also{id->state.update{it.copy(session=id)}}}
        api().prompt(dir(),id,text,state.value.provider,state.value.model)
        state.update{it.copy(agentBusy=true)};loadProject()
    }
    fun abort()=action {api().abort(dir(),state.value.session);refreshConversation()}
    fun permission(id:String,allow:Boolean)=action {api().permission(dir(),id,allow);refreshConversation()}
    fun answer(id:String,answers:List<List<String>>)=action {val array=JSONArray();answers.forEach{array.put(JSONArray(it))};api().answer(dir(),id,array);refreshConversation()}
    fun refreshProviders()=action {loadProviders()}
    private suspend fun loadProviders() {
        val data=api().providers(dir());val all=data.getJSONArray("all").objects()
        val connected=data.optJSONArray("connected")?:JSONArray()
        val ids=(0 until connected.length()).map{connected.getString(it)}.toSet()
        val models=all.filter{it.getString("id") in ids}.flatMap{p->
            val ms=p.getJSONObject("models");ms.keys().asSequence().map{id->ModelItem(p.getString("id"),id,ms.getJSONObject(id).optString("name",id))}.toList()
        }.sortedBy{it.label}
        state.update{it.copy(providers=all.map{p->p.getString("id") to p.optString("name",p.getString("id"))},models=models)}
    }
    fun connectProvider(provider:String,key:String)=action {
        require(provider.matches(Regex("[a-zA-Z0-9_.-]+")));require(key.isNotBlank()){tr(UiText.EnterApiKey)}
        api().auth(dir(),provider,key);loadProviders();state.update{it.copy(notice=tr(UiText.ProviderConnected))}
    }
    fun selectModel(model:ModelItem) {state.update{it.copy(provider=model.provider,model=model.id)};prefs.edit().putString("provider",model.provider).putString("model",model.id).apply()}
    fun showDiff()=action {check(state.value.session.isNotBlank()){ tr(UiText.ChooseConversation) };val diffs=api().diff(dir(),state.value.session).objects();state.update{it.copy(diffs=diffs)}}
    fun browse(folder:String)=action {loadFiles(folder)}
    private fun loadFiles(folder:String) {
        val project=projectFile();val directory=engine.workspace.resolve(project,folder)
        val files=directory.listFiles()?.filter{!java.nio.file.Files.isSymbolicLink(it.toPath())}?.sortedWith(compareBy<File>{!it.isDirectory}.thenBy{it.name})?.map{FileItem(it.relativeTo(project).invariantSeparatorsPath,it.isDirectory)}?:emptyList()
        state.update{it.copy(folder=folder,files=files,file=null)}
    }
    fun openFile(path:String)=action {val text=engine.workspace.text(projectFile(),path);state.update{it.copy(file=path,editor=text)}}
    fun edit(text:String){state.update{it.copy(editor=text)}}
    fun saveFile()=action {engine.workspace.save(projectFile(),checkNotNull(state.value.file),state.value.editor);state.update{it.copy(notice=tr(UiText.FileSaved))}}
    fun addFile(path:String)=action {require(path.isNotBlank());val file=engine.workspace.resolve(projectFile(),path);check(!file.exists()){ tr(UiText.FileExists) };engine.workspace.save(projectFile(),path,"");loadFiles(state.value.folder)}
    fun command(command:String)=action {require(command.isNotBlank());state.update{it.copy(terminal="$ $command\n")};val output=engine.command(listOf("/system/bin/sh","-c",command),projectFile());state.update{it.copy(terminal=it.terminal+output)}}
    fun startEngine(){state.update{it.copy(termux=false)};getApplication<Application>().startForegroundService(Intent(getApplication(),EngineService::class.java))}
    fun stopEngine(){getApplication<Application>().stopService(Intent(getApplication(),EngineService::class.java));engine.stop()}
    fun attach(port:Int,password:String,directory:String)=action {
        require(directory.startsWith("/")){tr(UiText.AbsoluteTermuxPath)}
        engine.attach(port,password);state.update{it.copy(termux=true,termuxDirectory=directory,project=directory.substringAfterLast('/'),session="",messages=emptyList())};loadProject();loadProviders()
    }
}
