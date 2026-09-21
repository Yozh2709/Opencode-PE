package dev.pocketopencode

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** All project paths are checked after canonicalization, including symlinks. */
class Workspace(val root: File) {
    init { root.mkdirs() }
    fun projects() = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
    fun create(name: String): File {
        require(name.matches(Regex("[\\p{L}\\p{N}_ .-]{1,64}")) && !name.startsWith(".")) { tr(UiText.ProjectNameRules) }
        val dir = resolve(root, name)
        require(!dir.exists()) { tr(UiText.ProjectExists) }
        check(dir.mkdir()) { tr(UiText.CreateProjectFailed) }
        return dir
    }
    fun resolve(base: File, relative: String): File {
        require(!relative.startsWith('/') && !relative.contains(':') && !File(relative).isAbsolute && !relative.contains('\\')) { tr(UiText.InvalidPath) }
        val basePath = base.canonicalFile.toPath()
        require(basePath.startsWith(root.canonicalFile.toPath())) { tr(UiText.OutsideWorkspace) }
        val target = File(base, relative).canonicalFile
        require(target.toPath().startsWith(basePath)) { tr(UiText.OutsideProject) }
        return target
    }
    fun importZip(input: InputStream, project: File, limit: Long = 256L * 1024 * 1024) {
        var bytes = 0L; var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 20_000) { tr(UiText.TooManyFiles) }
                val dest = resolve(project, entry.name)
                if (entry.isDirectory) dest.mkdirs() else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer); if (count < 0) break
                            bytes += count; require(bytes <= limit) { tr(UiText.ArchiveTooLarge) }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
    fun exportZip(project: File, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            project.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.forEach { file ->
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
                val rel = file.relativeTo(project).invariantSeparatorsPath
                resolve(project, rel)
                zip.putNextEntry(ZipEntry(rel)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    fun text(project: File, relative: String): String {
        val file = resolve(project, relative)
        require(file.length() <= 1024 * 1024) { tr(UiText.EditorSizeLimit) }
        val bytes = file.readBytes(); require(!bytes.contains(0)) { tr(UiText.BinaryFile) }
        return bytes.toString(Charsets.UTF_8)
    }
    fun save(project: File, relative: String, text: String) {
        val file = resolve(project, relative); file.parentFile?.mkdirs(); file.writeText(text)
    }
}
