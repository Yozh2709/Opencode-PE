package dev.pocketopencode

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

object TermuxArchive {
    private val q=TermuxBridge::quote
    suspend fun importZip(context: Context,input: InputStream,name: String): String = withContext(Dispatchers.IO) {
        val temporary=Files.createTempDirectory(context.cacheDir.toPath(),"termux-import-").toFile()
        try {
            val workspace=Workspace(File(temporary,"projects"))
            val project=workspace.create(name)
            workspace.importZip(input,project)
            val archive=File(temporary,"validated.zip")
            archive.outputStream().use { workspace.exportZip(project,it) }
            val target="${TermuxBridge.HOME}/pocket-projects/$name"
            LocalTransfer(mapOf("project.zip" to { out -> archive.inputStream().use { it.copyTo(out) }; Unit })).use { transfer ->
                TermuxBridge.execute(context,"""
                    set -eu
                    umask 077
                    root=${q(TermuxBridge.HOME+"/pocket-projects")}
                    target=${q(target)}
                    mkdir -p "${'$'}root"
                    if [ -e "${'$'}target" ]; then echo ${TermuxBridge.quote(tr(UiText.ProjectExists))} >&2; exit 1; fi
                    stage=${'$'}(mktemp -d "${'$'}root/.pocket-import.XXXXXX")
                    curl --fail --silent --show-error --max-time 180 ${q(transfer.url+"/project.zip")} -o "${'$'}stage/archive.zip"
                    mkdir "${'$'}stage/project"
                    unzip -q "${'$'}stage/archive.zip" -d "${'$'}stage/project" || { [ "${'$'}(stat -c %s "${'$'}stage/archive.zip")" = 22 ] || exit 1; }
                    mv -T -n "${'$'}stage/project" "${'$'}target"
                    test ! -d "${'$'}stage/project" || { echo ${TermuxBridge.quote(tr(UiText.ProjectExists))} >&2; exit 1; }
                    rm -f "${'$'}stage/archive.zip"
                    rmdir "${'$'}stage"
                """.trimIndent(),timeoutMs=240_000)
            }
            target
        } finally { temporary.deleteRecursively() }
    }
    suspend fun exportZip(context: Context,path: String,output: OutputStream) = withContext(Dispatchers.IO) {
        require(path.startsWith('/')) { tr(UiText.AbsolutePath) }
        val archive=File.createTempFile("termux-export-",".zip",context.cacheDir)
        try {
            var received=false
            LocalTransfer(emptyMap(),mapOf("project.zip" to { input,length ->
                archive.outputStream().use { out ->
                    var left=length; val buffer=ByteArray(65536)
                    while(left>0) { val count=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt()); check(count>0) { tr(UiText.TransferInterrupted) }; out.write(buffer,0,count); left-=count }
                }
                received=true
            })).use { transfer ->
                TermuxBridge.execute(context,"""
                    set -eu
                    umask 077
                    command -v zip >/dev/null || { echo ${TermuxBridge.quote(tr(UiText.InstallZip))} >&2; exit 1; }
                    stage=${'$'}(mktemp -d)
                    archive="${'$'}stage/project.zip"
                    trap 'rm -f "${'$'}archive"; rmdir "${'$'}stage"' EXIT
                    cd ${q(path)}
                    zip -r -q -y "${'$'}archive" . || { code=${'$'}?; [ "${'$'}code" = 12 ] || exit "${'$'}code"; printf 'PK\005\006\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000' > "${'$'}archive"; }
                    curl --fail --silent --show-error --max-time 180 -H 'Expect:' --upload-file "${'$'}archive" ${q(transfer.url+"/project.zip")}
                """.trimIndent(),timeoutMs=240_000)
                check(received) { tr(UiText.ArchiveNotReceived) }
                archive.inputStream().use { it.copyTo(output) }
            }
        } finally { archive.delete() }
    }
}
