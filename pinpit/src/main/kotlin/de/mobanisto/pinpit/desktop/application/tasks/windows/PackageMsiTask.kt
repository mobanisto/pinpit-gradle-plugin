/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.UnixUtils
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import de.mobanisto.pinpit.desktop.application.internal.provider
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject

abstract class PackageMsiTask @Inject constructor(
    target: Target
) : CustomPackageTask(target, TargetFormat.CustomMsi), WindowsTask {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(PackageMsiTask::class.java)
    }

    /** @see internal/wixToolset.kt */
    override val wixToolsetDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    val installationPath: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val licenseFile: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val launcherArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val launcherJvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val winPackageVersion: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winConsole: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winDirChooser: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winPerUserInstall: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winShortcut: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winMenu: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winMenuGroup: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val winUpgradeUuid: Property<String?> = objects.nullableProperty()

    @get:InputDirectory
    @get:Optional
    val runtimeImage: DirectoryProperty = objects.directoryProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val bitmapBanner: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val bitmapDialog: RegularFileProperty = objects.fileProperty()

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

    @OptIn(ExperimentalStdlibApi::class)
    override fun createPackage() {
        val environment: MutableMap<String, String> = HashMap<String, String>().apply {
            val wixDir = wixToolsetDir.ioFile
            val wixPath = wixDir.absolutePath
            val path = System.getenv("PATH") ?: ""
            put("PATH", "$wixPath;$path")
        }
        println("Using environment: $environment")
        val appImage = appImage.get().dir(packageName).get()
        println("app image: $appImage")
        for (file in appImage.asFileTree.files) {
            logger.debug("  $file")
        }

        val upgradeCode = winUpgradeUuid.get()
        val vendor = packageVendor.get()
        val productName = packageName.get()
        val version = winPackageVersion.get()
        val description = packageDescription.get()
        val bitmapBanner = this.bitmapBanner.orNull
        val bitmapDialog = this.bitmapDialog.orNull
        val icon = iconFile.get()

        val destination = destinationDir.get()
        logger.lifecycle("destination: $destination")
        destination.asFile.mkdirs()

        val destinationWix = destination.asPath().resolve("wix")
        Files.createDirectories(destinationWix)

        val outputFiles = destinationWix.resolve("Files.wxs")
        val outputProduct = destinationWix.resolve("Product.wxs")
        val executables = GenerateFilesWxs(outputFiles, appImage.asPath(), productName).execute()
        val mainExecutable = executables[0]
        GenerateProductWxs(
            outputProduct,
            upgradeCode!!,
            vendor!!,
            productName,
            version!!,
            description,
            mainExecutable,
            bitmapBanner?.asPath(),
            bitmapDialog?.asPath(),
            icon.asPath(),
        ).execute()

        val outputInstallDir = destinationWix.resolve("InstallDir.wxs")
        Thread.currentThread().contextClassLoader.getResourceAsStream("wix/InstallDir.wxs")?.use {
            Files.copy(it, outputInstallDir)
        }

        val wxsFiles = listOf(outputFiles, outputProduct, outputInstallDir)
        val wixobjFiles = wxsFiles.map {
            it.resolveSibling(it.fileName.toString().substringBeforeLast(".") + ".wixobj")
        }

        val wxsFilesWine = wxsFiles.map { winePaths(it) }
        val wixobjFilesWine = wixobjFiles.map { winePaths(it) }

        val msi = destination.asPath().resolve("${packageName.get()}-${target.arch.id}-${winPackageVersion.get()}.msi")
        val msiWine = winePaths(msi)

        val wixWine = winePaths(destinationWix)

        val candle = wixToolsetDir.file("candle.exe").get()
        val light = wixToolsetDir.file("light.exe").get()

        runExternalWindowsTool(
            tool = candle.asFile,
            args = buildList {
                addAll(wxsFilesWine)
                addAll(listOf("-dPlatform=x64", "-arch", "x64"))
                addAll(listOf("-out", wixWine))
            }
        )
        runExternalWindowsTool(
            tool = light.asFile,
            args = buildList {
                add("-sval")
                addAll(wixobjFilesWine)
                addAll(listOf("-ext", "WixUIExtension"))
                addAll(listOf("-out", msiWine))
            }
        )
    }

    private fun winePaths(path: Path): String {
        if (currentOS == Windows) {
            // Make sure to pass directories with trailing backslash. Important as candle won't treat them as
            // directories otherwise.
            return path.toString() + if (Files.isDirectory(path)) "\\" else ""
        }
        val output = runExternalToolAndGetOutput(
            tool = UnixUtils.winepath,
            // Make sure to pass directories with trailing slash so that resulting Windows paths returned by winepath
            // also carry a trailing backslash. Important as candle won't treat them as directories otherwise.
            args = listOf("-w", path.toString() + if (Files.isDirectory(path)) "/" else "")
        )
        return output.stdout.trim()
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
