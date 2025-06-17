/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.tasks.linux.PosixUtils.createDirectories
import de.mobanisto.pinpit.desktop.application.tasks.linux.PosixUtils.setPosixFilePermissions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Writer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Files.newBufferedWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute
import kotlin.io.path.readText
import kotlin.io.path.writeText

abstract class AbstractDebPackager(
    workingDir: Path,
    private val packageName: String,
    private val linuxPackageName: String,
    private val packageVersion: String,
    private val appCategory: String,
    private val menuGroup: String?,
    private val packageVendor: String,
    private val debMaintainer: String,
    private val packageDescription: String,
    private val depends: List<String>,
    private val debCopyright: Path?,
    private val debLauncher: Path?,
    private val debPreInst: Path?,
    private val debPostInst: Path?,
    private val debPreRm: Path?,
    private val debPostRm: Path?,
) {

    companion object {
        private const val permissionsRegular = "rw-r--r--"
        private const val permissionsExecutable = "rwxr-xr-x"
        internal val posixRegular = PosixFilePermissions.fromString(permissionsRegular)
        internal val posixExecutable = PosixFilePermissions.fromString(permissionsExecutable)
    }

    private val logger: Logger = LoggerFactory.getLogger(AbstractDebPackager::class.java)
    val debFileTree: Path = workingDir.resolve("debFileTree")

    internal fun buildDebFileTree(distributableApp: Path, debFileTree: Path) {
        val dirOpt = debFileTree.resolve("opt")
        val dirPackage = dirOpt.resolve(linuxPackageName)
        val dirBin = dirPackage.resolve("bin")
        val dirLib = dirPackage.resolve("lib")
        val dirShareDoc = dirPackage.resolve("share/doc/")
        dirShareDoc.createDirectories(asFileAttribute(posixExecutable))
        debCopyright?.copy(dirShareDoc.resolve("copyright"), posixRegular)

        val fileLauncher = dirLib.resolve("$linuxPackageName-$packageName.desktop")
        if (debLauncher == null) {
            createLauncherFile(fileLauncher)
        } else {
            debLauncher.copy(fileLauncher, posixRegular)
        }

        syncDir(distributableApp.resolve("bin"), dirBin)
        syncDir(distributableApp.resolve("lib"), dirLib) {
            it != Paths.get("app/.jpackage.xml")
        }
    }

    internal fun buildDebianDir(distributableApp: Path, debFileTree: Path) {
        val dirDebian = debFileTree.resolve("DEBIAN")
        dirDebian.createDirectories(asFileAttribute(posixExecutable))
        val fileControl = dirDebian.resolve("control")
        createControlFile(fileControl, distributableApp)
        debPreInst?.copy(dirDebian.resolve("preinst"), posixExecutable)
        debPostInst?.copy(dirDebian.resolve("postinst"), posixExecutable)
        debPreRm?.copy(dirDebian.resolve("prerm"), posixExecutable)
        debPostRm?.copy(dirDebian.resolve("postrm"), posixExecutable)
    }

    private fun Path.copy(target: Path, permissions: Set<PosixFilePermission>) {
        target.parent.createDirectories(asFileAttribute(posixExecutable))
        if (currentOS == Windows) {
            val content = this.readText()
            target.writeText(content.replace("\\r\\n?".toRegex(), "\n"))
        } else {
            Files.copy(this, target)
            target.setPosixFilePermissions(permissions)
        }
    }

    private fun createLauncherFile(fileLauncher: Path) {
        fileLauncher.parent.createDirectories(asFileAttribute(posixExecutable))
        newBufferedWriter(fileLauncher).use { writer ->
            writer.apply {
                writeLn("[Desktop Entry]")
                writeLn("Name=$packageName")
                writeLn("Comment=$packageDescription")
                writeLn("Exec=/opt/$linuxPackageName/bin/$packageName")
                writeLn("Icon=/opt/$linuxPackageName/lib/$packageName.png")
                writeLn("Terminal=false")
                writeLn("Type=Application")
                if (menuGroup != null) {
                    writeLn("Categories=$menuGroup")
                }
                writeLn("MimeType=")
            }
        }
    }

    private fun createControlFile(fileControl: Path, distributableApp: Path) {
        // Determine installed size as in jdk.jpackage.internal.LinuxDebBundler#createReplacementData()
        val sizeInBytes = sizeInBytes(distributableApp)
        val installedSize = (sizeInBytes shr 10).toString()
        logger.info("size in bytes: $sizeInBytes")
        logger.info("installed size: $installedSize")

        val list = mutableListOf<String>().apply {
            addAll(depends)
        }
        list.sort()

        newBufferedWriter(fileControl).use { writer ->
            writer.apply {
                writeLn("Package: $linuxPackageName")
                writeLn("Version: $packageVersion-1")
                writeLn("Section: $appCategory")
                writeLn("Maintainer: $packageVendor <$debMaintainer>")
                writeLn("Priority: optional")
                writeLn("Architecture: amd64")
                writeLn("Provides: $linuxPackageName")
                writeLn("Description: $packageDescription")
                writeLn("Depends: ${list.joinToString(", ")}")
                writeLn("Installed-Size: $installedSize")
            }
        }
    }

    // Same algorithm as jdk.jpackage.internal.PathGroup.Facade#sizeInBytes()
    private fun sizeInBytes(dir: Path): Long {
        var sum: Long = 0
        Files.walk(dir).use { stream ->
            sum += stream.filter { p -> Files.isRegularFile(p) }
                .mapToLong { f -> f.toFile().length() }.sum()
        }
        return sum
    }

    private fun Writer.writeLn(s: String? = null) {
        s?.let { write(it) }
        write("\n")
    }

    private fun syncDir(source: Path, target: Path, takeFile: (file: Path) -> Boolean = { _ -> true }) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = source.relativize(file)
                    if (!takeFile(relative)) {
                        return FileVisitResult.CONTINUE
                    }
                    val pathTarget = target.resolve(relative)
                    pathTarget.parent.createDirectories(asFileAttribute(posixExecutable))
                    if (Files.isExecutable(file)) {
                        file.setPosixFilePermissions(posixExecutable)
                    } else {
                        file.setPosixFilePermissions(posixRegular)
                    }
                    Files.copy(file, pathTarget)
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
}
