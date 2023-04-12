/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.macos

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
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecResult
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import javax.inject.Inject
import kotlin.io.path.isRegularFile

abstract class PackageMacosDistributableArchiveTask @Inject constructor(
    target: Target,
    override val targetFormat: DistributableArchive,
) : CustomPackageTask(target, targetFormat) {

    @get:Input
    @get:Optional
    val macosPackageName: Property<String?> = objects.nullableProperty()

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

        val appName = "${packageName.get()}.app"
        val distributableApp = distributableApp.asPath().resolve(appName)
        logger.lifecycle("distributable app: $distributableApp")

        logger.lifecycle("working dir: ${workingDir.get()}")
        fileOperations.delete(workingDir)

        val fullName = "${macosPackageName.get()}-${target.arch.id}-${packageVersion.get()}"
        val archive = destination.file("$fullName.${targetFormat.fileExt}")

        if (targetFormat.archiveFormat != ArchiveFormat.Zip) {
            throw GradleException("Invalid archive format for MacOS: ${targetFormat.archiveFormat}. Please use 'zip'")
        }

        archive.asFile.outputStream().use { fis ->
            ZipArchiveOutputStream(fis).use { zip ->
                Files.walkFileTree(
                    distributableApp,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            zip.packageFile(distributableApp, dir, appName)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            zip.packageFile(distributableApp, file, appName)
                            return FileVisitResult.CONTINUE
                        }
                    }
                )
            }
        }
    }

    private val permissions = arrayOf(
        OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
        GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
        OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE
    )

    private fun mode(posix: MutableSet<PosixFilePermission>): Int {
        var mode = 0
        for (i in permissions.indices) {
            if (posix.contains(permissions[i])) {
                mode = mode.or(1 shl (permissions.size - i - 1))
            }
        }
        return mode
    }

    private fun ZipArchiveOutputStream.packageFile(dir: Path, file: Path, prefix: String) {
        val relative = dir.relativize(file)
        val entry = createArchiveEntry(file, "$prefix/$relative") as ZipArchiveEntry
        val mode = if (currentOS.isUnix()) {
            mode(Files.getPosixFilePermissions(file))
        } else {
            // TODO: set permissions based on rules like for Linux
            "0755".toInt(8)
        }
        putArchiveEntry(entry)
        entry.unixMode = mode
        if (file.isRegularFile()) {
            Files.copy(file, this)
        }
        closeArchiveEntry()
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
