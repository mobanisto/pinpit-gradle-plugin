/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.validation.deb

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Writer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Files.createDirectories
import java.nio.file.Files.newBufferedWriter
import java.nio.file.Files.setPosixFilePermissions
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute
import kotlin.io.path.createDirectories

/**
 * A class for creating a DEB package using native tools fakeroot and dpkg-deb. This is used in the tests to make sure
 * our platform-independent DEB packaging matches native packaging.
 */
class NativeDebPackager constructor(
    private val appImage: Path,
    private val destinationDir: Path,
    workingDir: Path,
    private val packageName: String,
    private val linuxPackageName: String,
    private val packageVersion: String,
    private val arch: String,
    private val appCategory: String,
    private val packageVendor: String,
    private val debMaintainer: String,
    private val packageDescription: String,
    private val depends: List<String>,
    private val qualifier: String,
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

    private val logger: Logger = LoggerFactory.getLogger(NativeDebPackager::class.java)
    private val debFileTree: Path = workingDir.resolve("debFileTree")

    fun createPackage() {
        logger.info("destination: $destinationDir")
        destinationDir.createDirectories(asFileAttribute(posixExecutable))

        logger.info("app image: $appImage")

        logger.info("building debian file tree at: $debFileTree")
        debFileTree.createDirectories(asFileAttribute(posixExecutable))

        buildDebFileTree(appImage, debFileTree)
        buildDebianDir(appImage, debFileTree)

        val deb = destinationDir.resolve("$linuxPackageName-$qualifier-$arch-$packageVersion.deb")
        runExternalTool(
            tool = "/usr/bin/fakeroot",
            args = listOf("/usr/bin/dpkg-deb", "-b", debFileTree.toString(), deb.toString())
        )
    }

    private fun buildDebFileTree(appImage: Path, debFileTree: Path) {
        val dirOpt = debFileTree.resolve("opt")
        val dirPackage = dirOpt.resolve(linuxPackageName)
        val dirBin = dirPackage.resolve("bin")
        val dirLib = dirPackage.resolve("lib")
        val dirShareDoc = dirPackage.resolve("share/doc/")
        createDirectories(dirShareDoc, asFileAttribute(posixExecutable))
        debCopyright?.copy(dirShareDoc.resolve("copyright"), posixRegular)
        debLauncher?.copy(dirLib.resolve("$linuxPackageName-$packageName.desktop"), posixRegular)

        syncDir(appImage.resolve("bin"), dirBin)
        syncDir(appImage.resolve("lib"), dirLib) {
            it != Paths.get("app/.jpackage.xml")
        }
    }

    private fun buildDebianDir(appImage: Path, debFileTree: Path) {
        val dirDebian = debFileTree.resolve("DEBIAN")
        dirDebian.createDirectories(asFileAttribute(posixExecutable))
        val fileControl = dirDebian.resolve("control")
        createControlFile(fileControl, appImage)
        debPreInst?.copy(dirDebian.resolve("preinst"), posixExecutable)
        debPostInst?.copy(dirDebian.resolve("postinst"), posixExecutable)
        debPreRm?.copy(dirDebian.resolve("prerm"), posixExecutable)
        debPostRm?.copy(dirDebian.resolve("postrm"), posixExecutable)
    }

    private fun runExternalTool(tool: String, args: List<String>) {
        val cmdline = mutableListOf<String>().apply {
            add(tool)
            addAll(args)
        }
        val process = ProcessBuilder(cmdline).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw IllegalStateException("Command $args returned with value $exitCode")
    }

    private fun Path.copy(target: Path, permissions: Set<PosixFilePermission>) {
        target.parent.createDirectories(asFileAttribute(posixExecutable))
        Files.copy(this, target)
        setPosixFilePermissions(target, permissions)
    }

    private fun createControlFile(fileControl: Path, appImage: Path) {
        // Determine installed size as in jdk.jpackage.internal.LinuxDebBundler#createReplacementData()
        val sizeInBytes = sizeInBytes(appImage)
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
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = source.relativize(file)
                if (!takeFile(relative)) {
                    return FileVisitResult.CONTINUE
                }
                val pathTarget = target.resolve(relative)
                createDirectories(pathTarget.parent, asFileAttribute(posixExecutable))
                if (Files.isExecutable(file)) {
                    setPosixFilePermissions(file, posixExecutable)
                } else {
                    setPosixFilePermissions(file, posixRegular)
                }
                Files.copy(file, pathTarget)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
