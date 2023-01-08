/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.isUnix
import de.mobanisto.pinpit.desktop.application.tasks.linux.JvmDebPackager.SubArchive.CONTROL
import de.mobanisto.pinpit.desktop.application.tasks.linux.JvmDebPackager.SubArchive.DATA
import de.mobanisto.pinpit.desktop.application.tasks.linux.PosixUtils.createDirectories
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_GNU
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.System.currentTimeMillis
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Files.walkFileTree
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream

/**
 * A class for creating a DEB package without using native tools.
 */
class JvmDebPackager constructor(
    private val distributableApp: Path,
    private val destinationDeb: Path,
    workingDir: Path,
    packageName: String,
    linuxPackageName: String,
    packageVersion: String,
    appCategory: String,
    packageVendor: String,
    debMaintainer: String,
    packageDescription: String,
    depends: List<String>,
    debCopyright: Path?,
    debLauncher: Path?,
    debPreInst: Path?,
    debPostInst: Path?,
    debPreRm: Path?,
    debPostRm: Path?,
) : AbstractDebPackager(
    workingDir,
    packageName,
    linuxPackageName,
    packageVersion,
    appCategory,
    packageVendor,
    debMaintainer,
    packageDescription,
    depends,
    debCopyright,
    debLauncher,
    debPreInst,
    debPostInst,
    debPreRm,
    debPostRm
) {

    private val logger: Logger = LoggerFactory.getLogger(JvmDebPackager::class.java)
    private val debPackageDir: Path = workingDir.resolve("debContent")

    enum class SubArchive {
        CONTROL,
        DATA
    }

    fun createPackage() {
        logger.info("destination: $destinationDeb")
        destinationDeb.parent.createDirectories(asFileAttribute(posixExecutable))

        logger.info("distributable app: $distributableApp")

        logger.info("building debian file tree at: $debFileTree")
        debFileTree.createDirectories(asFileAttribute(posixExecutable))

        buildDebFileTree(distributableApp, debFileTree)
        buildDebianDir(distributableApp, debFileTree)

        logger.info("building debian archives at: $debPackageDir")
        debPackageDir.createDirectories(asFileAttribute(posixExecutable))

        packageDeb(destinationDeb, debPackageDir, debFileTree)
    }

    private fun packageDeb(deb: Path, debPackageDir: Path, debFileTree: Path) {
        val nameControl = "control.tar.xz"
        val nameData = "data.tar.xz"

        val fileControl = debPackageDir.resolve(nameControl)
        val fileData = debPackageDir.resolve(nameData)

        packageControl(fileControl, debFileTree)
        packageData(fileData, debFileTree)

        deb.outputStream().buffered().use { fos ->
            ArArchiveOutputStream(fos).use { ar ->
                val debianBinary = "2.0\n".toByteArray()
                val entryDebianBinary =
                    ArArchiveEntry(
                        "debian-binary",
                        debianBinary.size.toLong(),
                        0,
                        0,
                        "100644".toInt(radix = 8),
                        currentTimeMillis() / 1000
                    )
                ar.putArchiveEntry(entryDebianBinary)
                ar.write(debianBinary)
                ar.closeArchiveEntry()

                val entryControl = ar.createArchiveEntry(fileControl, nameControl)
                ar.putArchiveEntry(entryControl)
                Files.copy(fileControl, ar)
                ar.closeArchiveEntry()

                val entryData = ar.createArchiveEntry(fileData, nameData)
                ar.putArchiveEntry(entryData)
                Files.copy(fileData, ar)
                ar.closeArchiveEntry()
            }
        }
    }

    private fun packageControl(fileControl: Path, debFileTree: Path) {
        val debian = debFileTree.resolve("DEBIAN")
        packageTarXz(fileControl) {
            walkFileTree(
                debian,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        packageFile(CONTROL, debian, dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        packageFile(CONTROL, debian, file)
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }
    }

    private fun packageData(fileData: Path, debFileTree: Path) {
        packageTarXz(fileData) {
            walkFileTree(
                debFileTree,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val relative = debFileTree.relativize(dir)
                        if (relative == Paths.get("DEBIAN")) return FileVisitResult.SKIP_SUBTREE
                        packageFile(DATA, debFileTree, dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        packageFile(DATA, debFileTree, file)
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }
    }

    private fun TarArchiveOutputStream.packageFile(subArchive: SubArchive, dir: Path, file: Path) {
        val relative = dir.relativize(file)
        val entry = createArchiveEntry(file, "./$relative") as TarArchiveEntry
        entry.userId = 0
        entry.groupId = 0
        if (currentOS.isUnix()) {
            entry.mode = (if (file.isExecutable()) "755" else "644").toInt(radix = 8)
        } else {
            entry.mode = (permission(subArchive, file, relative)).toInt(radix = 8)
        }
        putArchiveEntry(entry)
        if (file.isRegularFile()) {
            Files.copy(file, this)
        }
        closeArchiveEntry()
    }

    private fun permission(subArchive: SubArchive, file: Path, relative: Path): String {
        when (subArchive) {
            CONTROL -> {
                // scripts located in DEBIAN source folder / control.tar.xz
                val scripts = setOf("preinst", "prerm", "postinst", "postrm")
                if (scripts.contains(relative.fileName.toString())) {
                    return "755"
                }
            }

            DATA -> {
                // some shared objects that are shipped in data.tar.xz
                val lib = setOf("libapplauncher.so") // in /opt/package-name/lib/
                val runtimeLib = setOf("jexec", "jspawnhelper") // in /opt/package-name/lib/runtime/

                // we don't have the path /opt/package-name available at this point, so guess only based on
                // parent directories "bin/", "lib/", "runtime/lib"
                val parent = relative.parent
                if (parent != null) {
                    if (relative.nameCount >= 2) {
                        val relevant = relative.subpath(relative.nameCount - 2, relative.nameCount - 1)
                        if (relevant == Paths.get("bin")) {
                            return "755"
                        }
                        if (relevant == Paths.get("lib")) {
                            if (lib.contains(relative.fileName.toString())) {
                                return "755"
                            }
                        }
                    }
                    if (relative.nameCount >= 4) {
                        val relevant = relative.subpath(relative.nameCount - 4, relative.nameCount - 1)
                        if (relevant == Paths.get("lib/runtime/lib")) {
                            if (runtimeLib.contains(relative.fileName.toString())) {
                                return "755"
                            }
                        }
                    }
                }
            }
        }
        return if (file.isDirectory()) "755" else "644"
    }

    private fun packageTarXz(outputFile: Path, fn: TarArchiveOutputStream.() -> Unit) {
        outputFile.outputStream().buffered().use { fos ->
            XZCompressorOutputStream(fos).use { xz ->
                TarArchiveOutputStream(xz).use { tar ->
                    tar.setLongFileMode(LONGFILE_GNU)
                    tar.fn()
                }
            }
        }
    }
}
