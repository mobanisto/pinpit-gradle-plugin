/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.UnixUtils
import de.mobanisto.pinpit.desktop.application.internal.files.SimpleFileCopyingProcessor
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.pinpit.desktop.application.internal.files.isJarFile
import de.mobanisto.pinpit.desktop.application.internal.files.mangledName
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.ioFileOrNull
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import de.mobanisto.pinpit.desktop.application.internal.provider
import de.mobanisto.pinpit.desktop.application.internal.stacktraceToString
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import de.mobanisto.pinpit.desktop.application.tasks.FilesMapping
import de.mobanisto.pinpit.desktop.application.tasks.isSkikoFor
import de.mobanisto.pinpit.desktop.application.tasks.unpackSkikoFor
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecResult
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.inject.Inject

abstract class PackageMsiTask @Inject constructor(
    target: Target
) : CustomPackageTask(target, TargetFormat.CustomMsi), WindowsTask {

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

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val icon: RegularFileProperty = objects.fileProperty()

    private lateinit var jvmRuntimeInfo: JvmRuntimeProperties

    @get:LocalState
    protected val jpackageResources: Provider<Directory> = project.layout.buildDirectory.dir("pinpit/tmp/resources")

    @get:LocalState
    protected val skikoDir: Provider<Directory> = project.layout.buildDirectory.dir("pinpit/tmp/skiko")

    @get:Internal
    private val libsDir: Provider<Directory> = workingDir.map {
        it.dir("libs")
    }

    @get:Internal
    private val packagedResourcesDir: Provider<Directory> = libsDir.map {
        it.dir("resources")
    }

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

    @get:Internal
    private val libsMappingFile: Provider<RegularFile> = workingDir.map {
        it.file("libs-mapping.txt")
    }

    @get:Internal
    private val libsMapping = FilesMapping()

    private fun invalidateMappedLibs(
        inputChanges: InputChanges
    ): Set<File> {
        val outdatedLibs = HashSet<File>()
        val libsDirFile = libsDir.ioFile

        fun invalidateAllLibs() {
            outdatedLibs.addAll(files.files)

            logger.debug("Clearing all files in working dir: $libsDirFile")
            fileOperations.delete(libsDirFile)
            libsDirFile.mkdirs()
        }

        if (inputChanges.isIncremental) {
            val allChanges = inputChanges.getFileChanges(files).asSequence()

            try {
                for (change in allChanges) {
                    libsMapping.remove(change.file)?.let { files ->
                        files.forEach { fileOperations.delete(it) }
                    }
                    if (change.changeType != ChangeType.REMOVED) {
                        outdatedLibs.add(change.file)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Could remove outdated libs incrementally: ${e.stacktraceToString()}")
                invalidateAllLibs()
            }
        } else {
            invalidateAllLibs()
        }

        return outdatedLibs
    }

    override fun prepareWorkingDir(inputChanges: InputChanges) {
        val libsDir = libsDir.ioFile
        val fileProcessor = SimpleFileCopyingProcessor

        val mangleJarFilesNames = mangleJarFilesNames.get()
        fun copyFileToLibsDir(sourceFile: File): File {
            val targetName =
                if (mangleJarFilesNames && sourceFile.isJarFile) sourceFile.mangledName()
                else sourceFile.name
            val targetFile = libsDir.resolve(targetName)
            fileProcessor.copy(sourceFile, targetFile)
            return targetFile
        }

        val outdatedLibs = invalidateMappedLibs(inputChanges)
        for (sourceFile in outdatedLibs) {
            assert(sourceFile.exists()) { "Lib file does not exist: $sourceFile" }

            libsMapping[sourceFile] = if (isSkikoFor(target, sourceFile)) {
                val unpackedFiles = unpackSkikoFor(target, sourceFile, skikoDir.ioFile, fileOperations)
                unpackedFiles.map { copyFileToLibsDir(it) }
            } else {
                listOf(copyFileToLibsDir(sourceFile))
            }
        }

        // todo: incremental copy
        cleanDirs(packagedResourcesDir)
        val destResourcesDir = packagedResourcesDir.ioFile
        val appResourcesDir = appResourcesDir.ioFileOrNull
        if (appResourcesDir != null) {
            for (file in appResourcesDir.walk()) {
                val relPath = file.relativeTo(appResourcesDir).path
                val destFile = destResourcesDir.resolve(relPath)
                if (file.isDirectory) {
                    fileOperations.mkdir(destFile)
                } else {
                    file.copyTo(destFile)
                }
            }
        }

        cleanDirs(jpackageResources)
    }

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
            println("  $file")
        }

        val upgradeCode = winUpgradeUuid.get()
        val vendor = packageVendor.get()
        val productName = packageName.get()
        val version = winPackageVersion.get()
        val description = packageDescription.get()
        val bitmapBanner = this.bitmapBanner.get()
        val bitmapDialog = this.bitmapDialog.get()
        val icon = this.icon.get()

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
            bitmapBanner.asPath(),
            bitmapDialog.asPath(),
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

        val candle = wixToolsetDir.file("candle.exe").get().toString()
        val light = wixToolsetDir.file("light.exe").get().toString()

        runExternalTool(
            tool = UnixUtils.wine,
            args = buildList {
                add(candle)
                addAll(wxsFilesWine)
                addAll(listOf("-dPlatform=x64", "-arch", "x64"))
                addAll(listOf("-out", wixWine))
            }
        )
        runExternalTool(
            tool = UnixUtils.wine,
            args = buildList {
                add(light)
                add("-sval")
                addAll(wixobjFilesWine)
                addAll(listOf("-ext", "WixUIExtension"))
                addAll(listOf("-out", msiWine))
            }
        )
    }

    private fun winePaths(path: Path): String {
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

        val mappingFile = libsMappingFile.ioFile
        if (mappingFile.exists()) {
            try {
                libsMapping.loadFrom(mappingFile)
            } catch (e: Exception) {
                fileOperations.delete(mappingFile)
                throw e
            }
            logger.debug("Loaded libs mapping from $mappingFile")
        }
    }

    override fun saveStateAfterFinish() {
        val mappingFile = libsMappingFile.ioFile
        libsMapping.saveTo(mappingFile)
        logger.debug("Saved libs mapping to $mappingFile")
    }
}
