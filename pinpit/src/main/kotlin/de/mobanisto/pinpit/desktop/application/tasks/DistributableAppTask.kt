/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks

import de.mobanisto.pinpit.desktop.application.dsl.MacOSSigningSettings
import de.mobanisto.pinpit.desktop.application.internal.APP_RESOURCES_DIR
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.MacSigner
import de.mobanisto.pinpit.desktop.application.internal.OS.Linux
import de.mobanisto.pinpit.desktop.application.internal.OS.MacOS
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.SKIKO_LIBRARY_PATH
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.files.MacJarSignFileCopyingProcessor
import de.mobanisto.pinpit.desktop.application.internal.files.SimpleFileCopyingProcessor
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findRelative
import de.mobanisto.pinpit.desktop.application.internal.files.isJarFile
import de.mobanisto.pinpit.desktop.application.internal.files.mangledName
import de.mobanisto.pinpit.desktop.application.internal.files.posixExecutable
import de.mobanisto.pinpit.desktop.application.internal.files.posixRegular
import de.mobanisto.pinpit.desktop.application.internal.files.syncDir
import de.mobanisto.pinpit.desktop.application.internal.files.writeLn
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.ioFileOrNull
import de.mobanisto.pinpit.desktop.application.internal.isUnix
import de.mobanisto.pinpit.desktop.application.internal.notNullProperty
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import de.mobanisto.pinpit.desktop.application.internal.provider
import de.mobanisto.pinpit.desktop.application.internal.stacktraceToString
import de.mobanisto.pinpit.desktop.application.internal.validation.validate
import de.mobanisto.pinpit.desktop.application.tasks.windows.WindowsTask
import org.gradle.api.file.ConfigurableFileCollection
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
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.ChangeType
import org.gradle.work.InputChanges
import java.io.File
import java.nio.file.Files
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.io.path.copyTo
import kotlin.io.path.isRegularFile

abstract class DistributableAppTask @Inject constructor(
    @Input val target: Target,
) : AbstractCustomTask(), WindowsTask {
    @get:InputFiles
    val files: ConfigurableFileCollection = objects.fileCollection()

    /**
     * A hack to avoid conflicts between jar files in a flat dir.
     * We receive input jar files as a list (FileCollection) of files.
     * At that point we don't have access to jar files' coordinates.
     *
     * Some files can have the same simple names.
     * For example, a project containing two modules:
     *  1. :data:utils
     *  2. :ui:utils
     * produces:
     *  1. <PROJECT>/data/utils/build/../utils.jar
     *  2. <PROJECT>/ui/utils/build/../utils.jar
     *
     *  jpackage expects all files to be in one input directory (not sure),
     *  so the natural workaround to avoid overwrites/conflicts is to add a content hash
     *  to a file name. A better solution would be to preserve coordinates or relative paths,
     *  but it is not straightforward at this point.
     *
     *  The flag is needed for two things:
     *  1. Give users the ability to turn off the mangling, if they need to preserve names;
     *  2. Proguard transformation already flattens jar files & mangles names, so we don't
     *  need to mangle twice.
     */
    @get:Input
    val mangleJarFilesNames: Property<Boolean> = objects.notNullProperty(true)

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val iconFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    val launcherMainClass: Property<String> = objects.notNullProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val launcherMainJar: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val launcherArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    val launcherJvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    val packageName: Property<String> = objects.notNullProperty()

    @get:Input
    @get:Optional
    val packageDescription: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageCopyright: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageVendor: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageVersion: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macPackageName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macDockName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macAppStore: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val macAppCategory: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macEntitlementsFile: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macRuntimeEntitlementsFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val packageBuildVersion: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macProvisioningProfile: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val macRuntimeProvisioningProfile: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val winConsole: Property<Boolean?> = objects.nullableProperty()

    @get:InputDirectory
    @get:Optional
    val runtimeImage: DirectoryProperty = objects.directoryProperty()

    @get:InputDirectory
    @get:Optional
    val distributableApp: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    internal val nonValidatedMacBundleID: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    internal val macExtraPlistKeysRawXml: Property<String?> = objects.nullableProperty()

    @get:InputFile
    @get:Optional
    val javaRuntimePropertiesFile: RegularFileProperty = objects.fileProperty()

    private lateinit var jvmRuntimeInfo: JvmRuntimeProperties

    @get:Optional
    @get:Nested
    internal var nonValidatedMacSigningSettings: MacOSSigningSettings? = null

    @get:Internal
    val jdkDir: Property<Path> = objects.notNullProperty()

    @get:Internal
    val jdkVersion: Property<Int> = objects.notNullProperty()

    private val macSigner: MacSigner? by lazy {
        val nonValidatedSettings = nonValidatedMacSigningSettings
        if (currentOS == MacOS && nonValidatedSettings?.sign?.get() == true) {
            val validatedSettings =
                nonValidatedSettings.validate(nonValidatedMacBundleID, project, macAppStore)
            MacSigner(validatedSettings, runExternalTool)
        } else null
    }

    @get:LocalState
    protected val signDir: Provider<Directory> = project.layout.buildDirectory.dir("pinpit/tmp/sign")

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
        val fileProcessor =
            macSigner?.let { signer ->
                val tmpDirForSign = signDir.ioFile
                fileOperations.delete(tmpDirForSign)
                tmpDirForSign.mkdirs()

                MacJarSignFileCopyingProcessor(
                    signer,
                    tmpDirForSign,
                    jvmRuntimeVersion = 17 // TODO: hardcoded version
                )
            } ?: SimpleFileCopyingProcessor

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

    override fun checkResult() {
        super.checkResult()
        val outputFile = destinationDir.ioFile
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

    override fun runTask() {
        val dir = destinationDir.asPath()

        val dirDistributableApp = dir.resolve(packageName.get())
        createDirectories(dirDistributableApp)
        logger.lifecycle("distributable app: $dirDistributableApp")

        val jpackageJMods = jdkDir.get().resolve("jmods/jdk.jpackage.jmod")

        when (target.os) {
            Linux -> {
                packageLinux(dirDistributableApp, jpackageJMods)
            }

            Windows -> {
                packageWindows(dirDistributableApp, jpackageJMods)
            }

            MacOS -> {
                packageMacOs()
            }
        }
    }

    private fun packageLinux(dirDistributableApp: Path, jpackageJMods: Path) {
        val dirBin = dirDistributableApp.resolve("bin")
        val dirLib = dirDistributableApp.resolve("lib")
        createDirectories(dirBin)
        createDirectories(dirLib)

        val dirRuntime = dirLib.resolve("runtime")
        val dirApp = dirLib.resolve("app")

        val resAppLauncher = "classes/jdk/jpackage/internal/resources/jpackageapplauncher"
        val launcher = dirBin.resolve(packageName.get())
        extractZip(jpackageJMods, resAppLauncher, launcher)
        if (currentOS.isUnix()) {
            Files.setPosixFilePermissions(launcher, posixExecutable)
        }
        if (jdkVersion.get() >= 17) {
            val resAppLauncherAux = "classes/jdk/jpackage/internal/resources/libjpackageapplauncheraux.so"
            val launcherLib = dirLib.resolve("libapplauncher.so")
            extractZip(jpackageJMods, resAppLauncherAux, launcherLib)
            if (currentOS.isUnix()) {
                Files.setPosixFilePermissions(launcherLib, posixExecutable)
            }
        }
        syncDir(runtimeImage.asPath(), dirRuntime)
        syncDir(libsDir.get().asPath(), dirApp)

        val icon = dirLib.resolve("${packageName.get()}.png")
        iconFile.asPath().copyTo(icon)
        if (currentOS.isUnix()) {
            Files.setPosixFilePermissions(icon, posixRegular)
        }

        val fileConfig = dirApp.resolve("${packageName.get()}.cfg")
        createConfig(fileConfig)
        if (currentOS.isUnix()) {
            Files.setPosixFilePermissions(fileConfig, posixRegular)
        }
    }

    private fun packageWindows(dirDistributableApp: Path, jpackageJMods: Path) {
        val dirRuntime = dirDistributableApp.resolve("runtime")
        val dirApp = dirDistributableApp.resolve("app")
        // The equivalent of JPackage's --win-console
        val resAppLauncher = if (winConsole.get()!!) {
            // Show console window while app is running
            "classes/jdk/jpackage/internal/resources/jpackageapplauncher.exe"
        } else {
            // Do not show console window while app is running
            "classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe"
        }

        val launcher = dirDistributableApp.resolve("${packageName.get()}.exe")
        extractZip(jpackageJMods, resAppLauncher, launcher)
        if (currentOS.isUnix()) {
            Files.setPosixFilePermissions(launcher, posixExecutable)
        }

        // Set icon and exe properties such as version, company, app name etc.
        rebrandExecutable(launcher)

        syncDir(runtimeImage.asPath(), dirRuntime)
        syncDir(libsDir.get().asPath(), dirApp)

        val fileConfig = dirApp.resolve("${packageName.get()}.cfg")
        createConfig(fileConfig)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun rebrandExecutable(launcher: Path) {
        val peRebrander = peRebranderDir.file("PE-Rebrander.exe").get()
        runExternalWindowsTool(
            tool = peRebrander.asFile,
            args = buildList {
                add(launcher.toString())
                if (iconFile.isPresent) {
                    addAll(listOf("--icon", iconFile.get().toString()))
                }
                val version = packageVersion.get()!!
                addAll(listOf("--file-version", "$version.0"))
                addAll(listOf("--product-version", version))
                packageVendor.orNull?.let { addAll(listOf("--company-name", it)) }
                packageName.orNull?.let { addAll(listOf("--product-name", it)) }
                packageCopyright.orNull?.let { addAll(listOf("--legal-copyright", it)) }
                packageDescription.orNull?.let { addAll(listOf("--file-description", it)) }
            }
        )
    }

    private fun packageMacOs() {
        // TODO: create binary by copying from JDK archive
    }

    private fun extractZip(zipFile: Path, zipResource: String, targetFile: Path) {
        ZipFile(zipFile.toFile()).use {
            val entry = it.getEntry(zipResource) ?: error("Unable to find '$zipResource' in '$zipFile'")
            Files.copy(it.getInputStream(entry), targetFile)
        }
    }

    private fun createConfig(fileConfig: Path) {
        val jars = findRelative(libsDir.get().asPath()) { file ->
            file.isRegularFile() && file.fileName.toString().endsWith(".jar")
        }
        jars.sort()

        val mainJarAbsolute = libsMapping[launcherMainJar.ioFile]?.singleOrNull()
            ?: error("Main jar was not processed correctly: ${launcherMainJar.ioFile}")
        val mainJar = libsDir.asPath().relativize(mainJarAbsolute.toPath())
        jars.remove(mainJar)

        fun appDir(vararg pathParts: String): String {
            /** For windows we need to pass '\\' to jpackage file, each '\' need to be escaped.
             * Otherwise '$APPDIR\resources' is passed to jpackage,
             * and '\r' is treated as a special character at run time.
             */
            val separator = if (target.os == Windows) "\\\\" else "/"
            return listOf("\$APPDIR", *pathParts).joinToString(separator) { it }
        }

        Files.newBufferedWriter(fileConfig, CREATE, WRITE, TRUNCATE_EXISTING).use {
            it.apply {
                writeLn("[Application]")
                // We intentionally write the main jar before the 'app.mainclass' property and the others afterwards,
                // as this is what JPackage seems to be doing.
                writeLn("app.classpath=${appDir(mainJar.toString())}")
                writeLn("app.mainclass=${launcherMainClass.get()}")
                // Attention: this assumes that jars are found only on the first level as we do not replace the
                // separator character for files further down in the hierarchy or make sure not to have \\-problems
                // on Windows. I think the way the libs are gathered, they will end up flat in the libs directory
                // though - Seb
                for (jar in jars) {
                    writeLn("app.classpath=${appDir(jar.toString())}")
                }
                writeLn()
                writeLn("[JavaOptions]")
                writeLn("java-options=-Djpackage.app-version=${packageVersion.get()}")
                writeLn("java-options=-D$APP_RESOURCES_DIR=${appDir(packagedResourcesDir.ioFile.name)}")
                launcherJvmArgs.get().forEach { arg ->
                    writeLn("java-options=$arg")
                }
                writeLn("java-options=-D$SKIKO_LIBRARY_PATH=${appDir()}")
                if (launcherArgs.get().isNotEmpty()) {
                    writeLn()
                    writeLn("[ArgOptions]")
                    launcherArgs.get().forEach { arg ->
                        writeLn("arguments=$arg")
                    }
                }
            }
        }

        println("launcher jvm args: ${launcherJvmArgs.get()}")
        println("launcher args: ${launcherArgs.get()}")
    }
}
