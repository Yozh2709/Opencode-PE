package dev.pocketopencode

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

private val Mint=Color(0xFFB8F78B)
private val PocketColors=darkColorScheme(primary=Mint,onPrimary=Color(0xFF16300C),background=Color(0xFF101513),surface=Color(0xFF171D19),surfaceVariant=Color(0xFF232C25),secondary=Color(0xFFB5CCBB),onSurface=Color(0xFFE6ECE6))

class ToolsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val locale by NativeLanguage.locale.collectAsState()
            key(nativeLanguage(locale)) { MaterialTheme(colorScheme=PocketColors) { PocketApp() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PocketApp(vm:PocketViewModel=viewModel()) {
    val state by vm.state.collectAsState();val engine by vm.engine.status.collectAsState()
    if(engine.termux) { TermuxProjectsScreen(engine); return }
    var tab by rememberSaveable {mutableIntStateOf(1)}
    var projectMenu by remember {mutableStateOf(false)}
    var dialog by remember {mutableStateOf("")};var name by remember {mutableStateOf("")};var url by remember {mutableStateOf("")}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.importZip(uri,name)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)vm.exportZip(uri)}
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(Unit){if(Build.VERSION.SDK_INT>=33)notification.launch(Manifest.permission.POST_NOTIFICATIONS)}
    val tabs=listOf(tr(UiText.Files) to Icons.Outlined.Folder,tr(UiText.Environment) to Icons.Outlined.Terminal)
    Scaffold(
        topBar={Column {TopAppBar(title={Column{Text("Opencode",fontWeight=FontWeight.Bold,fontSize=17.sp,maxLines=1);Text(engine.phase,fontSize=10.sp,maxLines=1,color=if(engine.ready)Mint else MaterialTheme.colorScheme.secondary)}},actions={
            Box {TextButton(onClick={projectMenu=true}){Text(state.project.ifBlank{tr(UiText.Projects)}.take(12),fontSize=12.sp,maxLines=1);Icon(Icons.Outlined.ExpandMore,null)}
                DropdownMenu(projectMenu,{projectMenu=false}){
                    state.projects.forEach{p->DropdownMenuItem(text={Text(p)},onClick={projectMenu=false;vm.selectProject(p)})}
                    HorizontalDivider()
                    listOf("create" to tr(UiText.NewProject),"zip" to tr(UiText.ImportZip),"clone" to tr(UiText.CloneGit)).forEach{(id,label)->DropdownMenuItem(text={Text(label)},onClick={projectMenu=false;name="";url="";dialog=id})}
                    if(state.project.isNotBlank()&&!state.termux)DropdownMenuItem(text={Text(tr(UiText.ExportZip))},onClick={projectMenu=false;exporter.launch("${state.project}.zip")})
                }
            }
        });if(state.working)LinearProgressIndicator(Modifier.fillMaxWidth())}},
        bottomBar={NavigationBar {tabs.forEachIndexed{i,(label,icon)->NavigationBarItem(selected=tab==i,onClick={tab=i;if(i==0&&state.project.isNotBlank())vm.browse(state.folder)},icon={Icon(icon,label)},label={Text(label,fontSize=10.sp)})}}}
    ){padding->Column(Modifier.padding(padding).fillMaxSize()){
        state.error?.let{ErrorBanner(it,vm::dismissError)}
        state.notice?.let{Text(it,color=Mint,modifier=Modifier.fillMaxWidth().clickable{vm.dismissError()}.padding(12.dp))}
        val context=androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick={
            context.startActivity(android.content.Intent(context,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("project",if(state.project.isNotBlank())java.io.File(vm.engine.workspace.root,state.project).absolutePath else ""))
        }){Text(tr(UiText.OpenProjectBack))}
        when(tab){
            0->FilesScreen(state,vm,{dialog="file";name=""})
            1->EnvironmentScreen(state,engine,vm)
        }
    }}
    if(dialog.isNotBlank())AlertDialog(onDismissRequest={dialog=""},title={Text(when(dialog){"zip"->tr(UiText.ImportProject);"clone"->tr(UiText.GitRepository);"file"->tr(UiText.NewFile);else->tr(UiText.NewProject)})},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        OutlinedTextField(name,{name=it},label={Text(if(dialog=="file")tr(UiText.FilePathHint) else tr(UiText.ProjectName))},singleLine=true)
        if(dialog=="clone")OutlinedTextField(url,{url=it},label={Text("https://…/repository.git")},singleLine=true)
        if(dialog=="zip")Text(tr(UiText.ImportZipHelp))
    }},confirmButton={TextButton(enabled=name.isNotBlank()&&!state.working,onClick={when(dialog){"zip"->importer.launch(arrayOf("application/zip","application/octet-stream"));"clone"->vm.cloneProject(name,url);"file"->vm.addFile(name);else->vm.createProject(name)};dialog=""}){Text(if(dialog=="zip")tr(UiText.ChooseZip) else tr(UiText.Create))}},dismissButton={TextButton(onClick={dialog=""}){Text(tr(UiText.Cancel))}})
}

@Composable private fun ErrorBanner(text:String,dismiss:()->Unit){Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(text,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.weight(1f),fontSize=12.sp);IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,tr(UiText.Close))}}}}
@Composable private fun EmptyState(icon:ImageVector,title:String,body:String,button:String?=null,action:()->Unit={}){Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=Mint,modifier=Modifier.size(42.dp));Spacer(Modifier.height(20.dp));Text(title,style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(10.dp));Text(body,color=MaterialTheme.colorScheme.secondary);if(button!=null){Spacer(Modifier.height(24.dp));Button(onClick=action){Text(button)}}}}

@Composable private fun ChatScreen(s:UiState,e:EngineStatus,vm:PocketViewModel,environment:()->Unit,settings:()->Unit,create:()->Unit){
    if(!e.ready){EmptyState(Icons.Outlined.Terminal,tr(UiText.CodeOnTheGo),tr(UiText.StartEmbeddedHelp),tr(UiText.OpenEnvironment),environment);return}
    if(s.project.isBlank()){EmptyState(Icons.Outlined.CreateNewFolder,tr(UiText.FirstProject),tr(UiText.CreateProjectHelp),tr(UiText.CreateProject),create);return}
    var prompt by rememberSaveable{mutableStateOf("")};var sessions by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){TextButton(onClick={sessions=true}){Text(s.sessions.firstOrNull{it.id==s.session}?.title?.take(30)?:tr(UiText.NewConversation));Icon(Icons.Outlined.ExpandMore,null)}
                DropdownMenu(sessions,{sessions=false}){s.sessions.forEach{item->DropdownMenuItem(text={Text(item.title.take(50))},onClick={sessions=false;vm.selectSession(item.id)})}}}
            IconButton(onClick=vm::newSession,enabled=!s.working){Icon(Icons.Outlined.Add,tr(UiText.NewConversation))}
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            if(s.messages.isEmpty())item{Text(tr(UiText.ProjectPrompt, s.project),fontSize=25.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(vertical=36.dp));Text(tr(UiText.AgentHelp),color=MaterialTheme.colorScheme.secondary)}
            items(s.messages,key={it.getJSONObject("info").getString("id")}){message->MessageCard(message)}
            items(s.permissions,key={it.getString("id")}){p->Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF333021))){Column(Modifier.padding(14.dp)){
                Text(tr(UiText.AllowAction),fontWeight=FontWeight.Bold);Text(p.optString("permission"),color=Mint);SelectionContainer{Text(p.optJSONArray("patterns")?.join("\n")?:"",fontFamily=FontFamily.Monospace,fontSize=12.sp)}
                Row{TextButton(onClick={vm.permission(p.getString("id"),false)}){Text(tr(UiText.Reject))};Button(onClick={vm.permission(p.getString("id"),true)}){Text(tr(UiText.AllowOnce))}}
            }}}
            items(s.questions,key={it.getString("id")}){q->QuestionCard(q){answers->vm.answer(q.getString("id"),answers)}}
            if(s.agentBusy)item{Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp);Text(tr(UiText.AgentWorking),color=Mint,fontSize=12.sp)}}
        }
        TextButton(onClick=settings,modifier=Modifier.padding(start=8.dp)){Text(if(s.model.isBlank())tr(UiText.ChooseModelArrow) else "${s.provider} / ${s.model}",fontSize=11.sp,maxLines=1)}
        Row(Modifier.navigationBarsPadding().imePadding().padding(start=12.dp,end=12.dp,bottom=12.dp),verticalAlignment=Alignment.Bottom){
            OutlinedTextField(prompt,{prompt=it},modifier=Modifier.weight(1f),placeholder={Text(tr(UiText.AgentTask))},maxLines=5,shape=RoundedCornerShape(18.dp))
            Spacer(Modifier.width(8.dp))
            if(s.agentBusy)FilledIconButton(onClick=vm::abort){Icon(Icons.Outlined.Stop,tr(UiText.StopTask))}
            else FilledIconButton(enabled=prompt.isNotBlank()&&!s.working&&s.model.isNotBlank(),onClick={vm.send(prompt);prompt=""}){Icon(Icons.AutoMirrored.Outlined.Send,tr(UiText.Send))}
        }
    }
}

@Composable private fun MessageCard(message:JSONObject){
    val info=message.getJSONObject("info");val user=info.optString("role")=="user"
    Surface(shape=RoundedCornerShape(16.dp),color=if(user)Color(0xFF263126) else MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(user)tr(UiText.You) else "OPENCODE",color=Mint,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
            message.optJSONArray("parts")?.objects()?.forEach{part->when(part.optString("type")){
                "text"->SelectionContainer{Text(part.optString("text"),fontSize=14.sp)}
                "reasoning"->{var open by remember{mutableStateOf(false)};Text(tr(UiText.Reasoning, if(open) "−" else "+"),modifier=Modifier.clickable{open=!open},fontSize=12.sp,color=MaterialTheme.colorScheme.secondary);if(open)Text(part.optString("text"),fontSize=12.sp)}
                "tool"->{val state=part.optJSONObject("state");var open by remember{mutableStateOf(false)}
                    Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.fillMaxWidth().clickable{open=!open}.padding(10.dp)){
                        Text("› ${part.optString("tool")} · ${state?.optString("status")}",fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Mint)
                        if(open)SelectionContainer{Text(listOf(state?.optJSONObject("input")?.toString(2),state?.optString("output"),state?.optString("error")).filterNotNull().filter{it.isNotBlank()}.joinToString("\n").take(25_000),fontSize=11.sp,fontFamily=FontFamily.Monospace)}
                    }}
                }
            }}
            info.optJSONObject("error")?.let{Text(it.optJSONObject("data")?.optString("message")?:it.toString(),color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable private fun QuestionCard(q:JSONObject,submit:(List<List<String>>)->Unit){
    val questions=q.getJSONArray("questions").objects();var answers by remember(q.getString("id")){mutableStateOf(List(questions.size){""})}
    Card{Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(tr(UiText.AgentQuestion),fontWeight=FontWeight.Bold)
        questions.forEachIndexed{i,question->
            Text(question.optString("question"));question.optJSONArray("options")?.objects()?.forEach{option->TextButton(onClick={answers=answers.toMutableList().also{it[i]=option.getString("label")}}){Text(option.getString("label"))}}
            OutlinedTextField(answers[i],{text->answers=answers.toMutableList().also{it[i]=text}},label={Text(tr(UiText.YourAnswer))},modifier=Modifier.fillMaxWidth())
        }
        Button(enabled=answers.all{it.isNotBlank()},onClick={submit(answers.map{listOf(it)})}){Text(tr(UiText.Answer))}
    }}
}

@Composable private fun FilesScreen(s:UiState,vm:PocketViewModel,add:()->Unit){
    if(s.termux){EmptyState(Icons.Outlined.Folder,tr(UiText.TermuxProject),tr(UiText.TermuxFilesHelp));return}
    if(s.project.isBlank()){EmptyState(Icons.Outlined.Folder,tr(UiText.NoProject),tr(UiText.ProjectsMenuHelp));return}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={if(s.file!=null)vm.browse(s.folder) else vm.browse(s.folder.substringBeforeLast('/',""))}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,tr(UiText.Back))}
            Text(s.file?:s.folder.ifBlank{s.project},modifier=Modifier.weight(1f),maxLines=1,fontSize=12.sp,fontFamily=FontFamily.Monospace)
            if(s.file!=null)TextButton(onClick=vm::saveFile,enabled=!s.working){Text(tr(UiText.Save))}
            else IconButton(onClick=add){Icon(Icons.Outlined.Add,tr(UiText.NewFile))}
        }
        if(s.file!=null)OutlinedTextField(s.editor,vm::edit,modifier=Modifier.fillMaxSize().padding(12.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp),label={Text("UTF-8")})
        else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp)){
            if(s.files.isEmpty())item{Text(tr(UiText.EmptyFolder),color=MaterialTheme.colorScheme.secondary,modifier=Modifier.padding(16.dp))}
            items(s.files,key={it.path}){file->ListItem(headlineContent={Text(file.path.substringAfterLast('/'),fontSize=14.sp)},leadingContent={Icon(if(file.directory)Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,null,tint=if(file.directory)Mint else MaterialTheme.colorScheme.secondary)},modifier=Modifier.clickable{if(file.directory)vm.browse(file.path) else vm.openFile(file.path)})}
        }
    }
}

@Composable private fun DiffScreen(s:UiState,vm:PocketViewModel){
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text(tr(UiText.AgentChanges),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));IconButton(onClick=vm::showDiff,enabled=s.session.isNotBlank()){Icon(Icons.Outlined.Refresh,tr(UiText.Refresh))}}
        if(s.diffs.isEmpty())EmptyState(Icons.AutoMirrored.Outlined.CompareArrows,tr(UiText.NoChanges),tr(UiText.ChangesHelp))
        else LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(s.diffs){diff->var expanded by remember{mutableStateOf(false)}
                Card{Column(Modifier.fillMaxWidth().padding(14.dp)){
                    Text(diff.optString("file"),color=Mint,fontFamily=FontFamily.Monospace,modifier=Modifier.clickable{expanded=!expanded})
                    Text("+${diff.optInt("additions")}  −${diff.optInt("deletions")}",fontSize=12.sp)
                    if(expanded){Text(tr(UiText.Before),color=MaterialTheme.colorScheme.error,fontSize=10.sp);SelectionContainer{Text(diff.optString("before").take(30_000),fontFamily=FontFamily.Monospace,fontSize=11.sp)};HorizontalDivider(Modifier.padding(vertical=10.dp));Text(tr(UiText.After),color=Mint,fontSize=10.sp);SelectionContainer{Text(diff.optString("after").take(30_000),fontFamily=FontFamily.Monospace,fontSize=11.sp)}}
                }}
            }
        }
    }
}

@Composable private fun EnvironmentScreen(s:UiState,e:EngineStatus,vm:PocketViewModel){
    val logs by vm.engine.logs.collectAsState();var command by rememberSaveable{mutableStateOf("node --version")}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text(tr(UiText.DeviceEnvironment),style=MaterialTheme.typography.headlineSmall)
        Text("OpenCode 1.18.31 · ARM64",color=Mint)
        Text(tr(UiText.EmbeddedToolsHelp),color=MaterialTheme.colorScheme.secondary)
        Text(tr(UiText.ExperimentalBuild),fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button(onClick=vm::startEngine,enabled=!e.ready&&!e.phase.contains("…")){Icon(Icons.Outlined.PlayArrow,null);Text(tr(UiText.Start))}
            OutlinedButton(onClick=vm::stopEngine){Text(tr(UiText.Stop))}
        }
        e.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        HorizontalDivider()
        Text(tr(UiText.ProjectCommand),style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(command,{command=it},modifier=Modifier.fillMaxWidth(),label={Text(tr(UiText.ShellCommand))},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp))
        Button(onClick={vm.command(command)},enabled=s.project.isNotBlank()&&!s.working&&!s.termux){Text(tr(UiText.Run))}
        Text(tr(UiText.NpmHelp),fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
        if(s.terminal.isNotBlank())Console(s.terminal)
        Text(tr(UiText.CoreLog),style=MaterialTheme.typography.titleMedium)
        Console(logs.ifBlank{tr(UiText.CoreNotStarted)})
    }
}
@Composable private fun Console(text:String){Surface(shape=RoundedCornerShape(12.dp),color=Color(0xFF080D0A),modifier=Modifier.fillMaxWidth()){SelectionContainer{Text(text,fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFFAFCAAF),modifier=Modifier.padding(12.dp))}}}

@Composable private fun SettingsScreen(s:UiState,e:EngineStatus,vm:PocketViewModel){
    var provider by rememberSaveable{mutableStateOf("anthropic")};var key by remember{mutableStateOf("")};var providerMenu by remember{mutableStateOf(false)};var modelMenu by remember{mutableStateOf(false)}
    var termux by remember{mutableStateOf(false)};var port by rememberSaveable{mutableStateOf("4096")};var password by remember{mutableStateOf("")};var directory by rememberSaveable{mutableStateOf("/data/data/com.termux/files/home/project")}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text(tr(UiText.ModelConnection),style=MaterialTheme.typography.headlineSmall)
        Text(tr(UiText.ProviderListHelp),color=MaterialTheme.colorScheme.secondary)
        if(!e.ready)Text(tr(UiText.StartCoreFirst),color=Mint)
        Box {OutlinedButton(onClick={providerMenu=true},enabled=e.ready){Text(s.providers.firstOrNull{it.first==provider}?.second?:provider);Icon(Icons.Outlined.ExpandMore,null)}
            DropdownMenu(providerMenu,{providerMenu=false}){s.providers.forEach{(id,label)->DropdownMenuItem(text={Text(label)},onClick={provider=id;providerMenu=false})}}
        }
        OutlinedTextField(key,{key=it},label={Text(tr(UiText.ProviderApiKey))},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(enabled=e.ready&&key.isNotBlank()&&!s.working,onClick={vm.connectProvider(provider,key);key=""}){Text(tr(UiText.ConnectThroughOpenCode))}
        Text(tr(UiText.KeyStorageHelp),fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
        HorizontalDivider()
        Text(tr(UiText.ActiveModel),style=MaterialTheme.typography.titleMedium)
        Box {OutlinedButton(onClick={modelMenu=true},enabled=s.models.isNotEmpty()){Text(s.model.ifBlank{tr(UiText.ChooseModel)},maxLines=2);Icon(Icons.Outlined.ExpandMore,null)}
            DropdownMenu(modelMenu,{modelMenu=false}){s.models.forEach{model->DropdownMenuItem(text={Column{Text(model.label);Text("${model.provider} / ${model.id}",fontSize=10.sp)}},onClick={vm.selectModel(model);modelMenu=false})}}
        }
        if(s.models.isEmpty()&&e.ready)Text(tr(UiText.ConnectProviderHelp),fontSize=12.sp)
        TextButton(onClick=vm::refreshProviders,enabled=e.ready){Text(tr(UiText.RefreshList))}
        HorizontalDivider()
        TextButton(onClick={termux=!termux}){Text(tr(UiText.AdvancedTermux, if(termux) "−" else "+"))}
        if(termux){
            Text(tr(UiText.ManualTermuxHelp),fontSize=12.sp)
            OutlinedTextField(port,{port=it.filter(Char::isDigit)},label={Text(tr(UiText.Port))},singleLine=true)
            OutlinedTextField(password,{password=it},label={Text(tr(UiText.ServerPassword))},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            OutlinedTextField(directory,{directory=it},label={Text(tr(UiText.TermuxProjectPath))},modifier=Modifier.fillMaxWidth())
            Button(onClick={vm.attach(port.toIntOrNull()?:0,password,directory)},enabled=!s.working){Text(tr(UiText.Connect))}
        }
        Text(tr(UiText.About, BuildConfig.VERSION_NAME),fontSize=11.sp,color=MaterialTheme.colorScheme.secondary)
    }
}
