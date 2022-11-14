package de.mobanisto.compose.validation.deb

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.junit.jupiter.api.Assertions
import java.io.InputStream
import java.lang.Integer.toOctalString
import java.nio.file.Paths

object ValidateDeb {
    fun validate(fis: InputStream) {
        // scripts located in DEBIAN source folder / control.tar.xz
        val scripts = setOf("preinst", "prerm", "postinst", "postrm")
        // some shared objects that are shipped in data.tar.xz
        val executables = setOf("libapplauncher.so", "jexec", "jspawnhelper")
        val ais = ArArchiveInputStream(fis)
        while (true) {
            val entry1: ArArchiveEntry = ais.nextArEntry ?: break
            val name1 = entry1.name
            println("$name1: ${entry1.groupId}, ${entry1.userId}, ${toOctalString(entry1.mode)}")
            if (name1.endsWith("tar.xz")) {
                val xz = XZCompressorInputStream(ais)
                val tar = TarArchiveInputStream(xz)
                while (true) {
                    val entry2 = tar.nextTarEntry ?: break
                    val name2 = entry2.name
                    val simplePath = name2.substring(2) // strip "./"
                    val path = Paths.get(simplePath)
                    val expectExecutable = entry2.isDirectory or scripts.contains(simplePath) or
                            (path.parent != null && path.parent.fileName.toString() == "bin") or
                            executables.contains(path.fileName.toString())
                    println("  $name2: ${entry2.longGroupId}, ${entry2.longUserId}, ${toOctalString(entry2.mode)}")
                    if (expectExecutable) {
                        Assertions.assertEquals("755", toOctalString(entry2.mode))
                    } else {
                        Assertions.assertEquals("644", toOctalString(entry2.mode))
                    }
                }
            }
        }
    }
}
