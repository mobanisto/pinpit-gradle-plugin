/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import de.mobanisto.pinpit.desktop.application.dsl.ArchiveFormat
import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat.DistributableArchive
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import de.mobanisto.pinpit.desktop.application.internal.provider
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
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
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import kotlin.io.path.isRegularFile

abstract class PackageWindowsDistributableArchiveTask @Inject constructor(
    target: Target,
    override val targetFormat: DistributableArchive,
) : CustomPackageTask(target, targetFormat) {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val licenseFile: RegularFileProperty = objects.fileProperty()

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

        val appImage = appImage.get().dir(packageName).get()
        logger.lifecycle("app image: $appImage")

        logger.lifecycle("working dir: ${workingDir.get()}")
        fileOperations.delete(workingDir)

        val fullName = "${packageName.get()}-${target.arch.id}-${packageVersion.get()}"
        val archive = destination.file("$fullName.${targetFormat.fileExt}")

        val pathAppImage = appImage.asPath()

        if (targetFormat.archiveFormat != ArchiveFormat.Zip) {
            throw GradleException("Invalid archive format for Windows: ${targetFormat.archiveFormat}. Please use 'zip'")
        }

        archive.asFile.outputStream().use { fis ->
            ZipArchiveOutputStream(fis).use { zip ->
                Files.walkFileTree(
                    pathAppImage,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            zip.packageFile(pathAppImage, dir, fullName)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            zip.packageFile(pathAppImage, file, fullName)
                            return FileVisitResult.CONTINUE
                        }
                    }
                )
            }
        }
    }

    private fun ZipArchiveOutputStream.packageFile(dir: Path, file: Path, prefix: String) {
        val relative = dir.relativize(file)
        val entry = createArchiveEntry(file, "$prefix/$relative") as ZipArchiveEntry
        putArchiveEntry(entry)
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
