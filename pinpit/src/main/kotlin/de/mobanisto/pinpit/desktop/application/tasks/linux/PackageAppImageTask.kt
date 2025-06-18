/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat
import de.mobanisto.pinpit.desktop.application.internal.Arch
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import de.mobanisto.pinpit.desktop.application.internal.provider
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import de.topobyte.squashfs.compression.ZstdCompression
import de.topobyte.squashfs.tools.SquashConvertDirectory
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.process.ExecResult
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.inject.Inject


abstract class PackageAppImageTask @Inject constructor(
    target: Target,
    override val targetFormat: TargetFormat,
) : CustomPackageTask(target, targetFormat) {

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
        val image = destination.file("$fullName${targetFormat.fileExt}")

        val pathDistributableApp = distributableApp.asPath()

        val filePreImage = Files.createTempFile("appimage", null)
        filePreImage.toFile().deleteOnExit()

        logger.lifecycle("Input: $pathDistributableApp")
        logger.lifecycle("Output: $image")
        logger.lifecycle("Temporary squash fs: $filePreImage")

        // Download appimage runtime
        val urlRuntime = "https://github.com/AppImage/type2-runtime/releases/download/continuous/" +
            when (target.arch) {
                Arch.X64 -> "runtime-x86_64"
                Arch.Arm64 -> "runtime-aarch64"
            }
        val dataRuntime = URL(urlRuntime).openStream().use {
            it.readBytes()
        }
        logger.lifecycle("Size of appimage runtime: {}", dataRuntime.size)

        // TODO: Create copy
        val pathImage = pathDistributableApp
        Files.createSymbolicLink(pathImage.resolve("AppRun"), Paths.get("bin/${packageName.get()}"))

        val task = SquashConvertDirectory()
        task.convertToSquashFs(
            pathImage, filePreImage, ZstdCompression(8), dataRuntime.size
        )

        // Replace target file with the one we just created
        Files.copy(
            filePreImage, image.asPath(), StandardCopyOption.REPLACE_EXISTING
        )

        // Prepend runtime
        val os = Files.newOutputStream(
            image.asPath(), StandardOpenOption.WRITE
        )
        os.write(dataRuntime)
        os.close()

        // Set executable flag on the target file
        image.asFile.setExecutable(true)
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
