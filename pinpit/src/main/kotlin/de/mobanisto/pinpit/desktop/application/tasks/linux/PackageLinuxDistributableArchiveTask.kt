/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.dsl.ArchiveFormat
import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat.DistributableArchive
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.isUnix
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import de.mobanisto.pinpit.desktop.application.internal.provider
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecResult
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

abstract class PackageLinuxDistributableArchiveTask @Inject constructor(
    target: Target,
    override val targetFormat: DistributableArchive,
) : CustomPackageTask(target, targetFormat) {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val licenseFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val linuxPackageName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageVersion: Property<String?> = objects.nullableProperty()

    private lateinit var jvmRuntimeInfo: JvmRuntimeProperties

    @get:Internal
    val appResourcesDir: DirectoryProperty = objects.directoryProperty()

    /**
     * Gradle runtime verification fails,
     * if InputDirectory is not null, but a directory does not exist.
     * The directory might not exist, because prepareAppResources task
     * does not create output directory if there are no resources.
     *
     * To work around this, appResourcesDir is used as a real property,
     * but it is annotated as @Internal, so it ignored during inputs checking.
     * This property is used only for inputs checking.
     * It returns appResourcesDir value if the underlying directory exists.
     */
    @Suppress("unused")
    @get:InputDirectory
    @get:Optional
    internal val appResourcesDirInputDirHackForVerification: Provider<Directory?>
        get() = provider { appResourcesDir.orNull?.let { if (it.asFile.exists()) it else null } }

    override fun createPackage() {
        val destination = destinationDir.get()
        logger.lifecycle("destination: $destination")
        destination.asFile.mkdirs()

        val distributableApp = distributableApp.get().dir(packageName).get()
        logger.lifecycle("distributable app: $distributableApp")

        logger.lifecycle("working dir: ${workingDir.get()}")
        fileOperations.delete(workingDir)

        val fullName = "${linuxPackageName.get()}-${target.arch.id}-${packageVersion.get()}"
        val archive = destination.file("$fullName.${targetFormat.fileExt}")

        val pathDistributableApp = distributableApp.asPath()

        if (targetFormat.archiveFormat != ArchiveFormat.TarGz) {
            throw GradleException("Invalid archive format for Linux: ${targetFormat.archiveFormat}. Please use 'tar.gz'")
        }

        archive.asFile.outputStream().use { fis ->
            GZIPOutputStream(fis).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    Files.walkFileTree(
                        pathDistributableApp,
                        object : SimpleFileVisitor<Path>() {
                            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                tar.packageFile(pathDistributableApp, dir, fullName)
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                tar.packageFile(pathDistributableApp, file, fullName)
                                return FileVisitResult.CONTINUE
                            }
                        }
                    )
                }
            }
        }
    }

    private fun TarArchiveOutputStream.packageFile(dir: Path, file: Path, prefix: String) {
        val relative = dir.relativize(file)
        val entry = createArchiveEntry(file, "$prefix/$relative") as TarArchiveEntry
        entry.userId = 0
        entry.groupId = 0
        if (currentOS.isUnix()) {
            entry.mode = (if (file.isExecutable()) "755" else "644").toInt(radix = 8)
        } else {
            entry.mode = (permission(file, relative)).toInt(radix = 8)
        }
        putArchiveEntry(entry)
        if (file.isRegularFile()) {
            Files.copy(file, this)
        }
        closeArchiveEntry()
    }

    private fun permission(file: Path, relative: Path): String {
        // some shared objects that are shipped
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
        return if (file.isDirectory()) "755" else "644"
    }

    override fun checkResult(result: ExecResult) {
        super.checkResult(result)
        val outputFile = findOutputFileOrDir(destinationDir.ioFile, targetFormat)
        logger.lifecycle("The distribution is written to ${outputFile.canonicalPath}")
    }

    override fun initState() {
        jvmRuntimeInfo = JvmRuntimeProperties.readFromFile(javaRuntimePropertiesFile.ioFile)
    }
}
