package dev.pocketopencode

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceTest {
    private fun zip(name:String,text:String):ByteArray {
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{it.putNextEntry(ZipEntry(name));it.write(text.toByteArray());it.closeEntry()};return out.toByteArray()
    }
    @Test fun archiveRoundTripPreservesNestedUnicodeFiles(){
        val root=Files.createTempDirectory("workspace").toFile()
        try{val ws=Workspace(root);val p=ws.create("Тест");ws.importZip(ByteArrayInputStream(zip("src/привет.js","console.log('hello')")),p)
            assertEquals("console.log('hello')",ws.text(p,"src/привет.js"));val out=ByteArrayOutputStream();ws.exportZip(p,out);val copy=ws.create("copy");ws.importZip(ByteArrayInputStream(out.toByteArray()),copy);assertEquals(ws.text(p,"src/привет.js"),ws.text(copy,"src/привет.js"))
        }finally{root.deleteRecursively()}
    }
    @Test fun rejectsZipTraversalAndOversizedInflation(){
        val root=Files.createTempDirectory("workspace").toFile()
        try{val ws=Workspace(root);val p=ws.create("project")
            for(name in listOf("../outside","/absolute","a\\..\\outside"))assertThrows(IllegalArgumentException::class.java){ws.importZip(ByteArrayInputStream(zip(name,"x")),p)}
            assertFalse(root.resolve("outside").exists())
            assertThrows(IllegalArgumentException::class.java){ws.importZip(ByteArrayInputStream(zip("large.txt","a".repeat(1024))),p,32)}
        }finally{root.deleteRecursively()}
    }
    @Test fun rejectsDuplicateProjectAndParentPaths(){
        val root=Files.createTempDirectory("workspace").toFile()
        try{val ws=Workspace(root);ws.create("project");assertThrows(IllegalArgumentException::class.java){ws.create("project")};assertThrows(IllegalArgumentException::class.java){ws.create("../escape")};assertThrows(IllegalArgumentException::class.java){ws.resolve(root,"../../escape")}}
        finally{root.deleteRecursively()}
    }
}
