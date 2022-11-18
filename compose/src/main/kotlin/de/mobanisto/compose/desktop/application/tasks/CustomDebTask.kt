/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.desktop.application.tasks

import de.mobanisto.compose.desktop.application.dsl.TargetFormat
import de.mobanisto.compose.desktop.application.internal.DebianUtils
import de.mobanisto.compose.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.compose.desktop.application.internal.dir
import de.mobanisto.compose.desktop.application.internal.files.SimpleFileCopyingProcessor
import de.mobanisto.compose.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.compose.desktop.application.internal.files.isJarFile
import de.mobanisto.compose.desktop.application.internal.files.mangledName
import de.mobanisto.compose.desktop.application.internal.files.writeLn
import de.mobanisto.compose.desktop.application.internal.ioFile
import de.mobanisto.compose.desktop.application.internal.ioFileOrNull
import de.mobanisto.compose.desktop.application.internal.nullableProperty
import de.mobanisto.compose.desktop.application.internal.provider
import de.mobanisto.compose.desktop.application.internal.stacktraceToString
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
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.io.path.createDirectories


abstract class CustomDebTask @Inject constructor() : CustomPackageTask(TargetFormat.CustomDeb) {

    companion object {
        private val PACKAGE_NAME_REGEX: Pattern = Pattern.compile("^(^\\S+):")
        private val LIB_IN_LDD_OUTPUT_REGEX = Pattern.compile("^\\s*\\S+\\s*=>\\s*(\\S+)\\s+\\(0[xX]\\p{XDigit}+\\)")
    }

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
    val packageVersion: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxShortcut: Property<Boolean?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxPackageName: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxAppRelease: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxAppCategory: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxDebPackageVersion: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxDebMaintainer: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxMenuGroup: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val linuxRpmLicenseType: Property<String?> = objects.nullableProperty()

    @get:InputDirectory
    @get:Optional
    val runtimeImage: DirectoryProperty = objects.directoryProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebPreInst: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebPostInst: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebPreRm: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebPostRm: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val linuxDebAdditionalDependencies: ListProperty<String> = objects.listProperty(String::class.java)

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebCopyright: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebLauncher: RegularFileProperty = objects.fileProperty()

    private lateinit var jvmRuntimeInfo: JvmRuntimeProperties

    @get:LocalState
    protected val jpackageResources: Provider<Directory> = project.layout.buildDirectory.dir("mocompose/tmp/resources")

    @get:LocalState
    protected val skikoDir: Provider<Directory> = project.layout.buildDirectory.dir("mocompose/tmp/skiko")

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
    private val debFileTree: Provider<Directory> = workingDir.dir("debFileTree")

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

            libsMapping[sourceFile] = if (isSkikoForCurrentOS(sourceFile)) {
                val unpackedFiles = unpackSkikoForCurrentOS(sourceFile, skikoDir.ioFile, fileOperations)
                unpackedFiles.map { copyFileToLibsDir(it) }
            } else {
                listOf(copyFileToLibsDir(sourceFile))
            }
        }

        // todo: incremental copy
        cleanDirs(packagedResourcesDir)
        if (appResourcesDir.isPresent) {
            syncDir(appResourcesDir.get(), packagedResourcesDir.get())
        }

        cleanDirs(jpackageResources)
    }

    override fun createPackage() {
        downloadJdk("linux", "x64")

        val destination = destinationDir.get()
        logger.lifecycle("destination: $destination")
        destination.asFile.mkdirs()

        val appImage = appImage.get().dir(packageName).get()
        logger.lifecycle("app image: $appImage")

        val debFileTree = debFileTree.get()
        logger.lifecycle("building debian file tree at: $debFileTree")
        debFileTree.asFile.mkdirs()

        val debArch = getDebArch()

        fileOperations.delete(debFileTree)
        buildDebFileTree(appImage, debFileTree)
        buildDebianDir(appImage, debFileTree, debArch)

        val deb = destination.file("${linuxPackageName.get()}_${linuxDebPackageVersion.get()}-1_${debArch}.deb")
        runExternalTool(
            tool = DebianUtils.fakeroot,
            args = listOf(DebianUtils.dpkgDeb.toString(), "-b", debFileTree.toString(), deb.toString())
        )
    }

    private val permissionsRegular = "rw-r--r--"
    private val permissionsExecutable = "rwxr-xr-x"
    private val posixRegular = PosixFilePermissions.fromString(permissionsRegular)
    private val posixExecutable = PosixFilePermissions.fromString(permissionsExecutable)

    private fun buildDebFileTree(appImage: Directory, debFileTree: Directory) {
        val dirOpt = debFileTree.dir("opt")
        val dirPackage = dirOpt.dir(linuxPackageName.get())
        val dirBin = dirPackage.dir("bin")
        val dirLib = dirPackage.dir("lib")
        val dirShareDoc = dirPackage.dir("share/doc/")
        createDirectories(dirShareDoc.asFile.toPath(), asFileAttribute(posixExecutable))
        linuxDebCopyright.copy(dirShareDoc.file("copyright"), posixRegular)
        linuxDebLauncher.copy(dirLib.file("${linuxPackageName.get()}-${packageName.get()}.desktop"), posixRegular)

        syncDir(appImage.dir("bin"), dirBin)
        syncDir(appImage.dir("lib"), dirLib) {
            it != Paths.get("app/.jpackage.xml")
        }
    }

    private fun getDebArch(): String {
        val result = runExternalToolAndGetOutput(
            tool = DebianUtils.dpkg,
            args = listOf("--print-architecture")
        )
        return result.stdout.lines()[0]
    }

    private fun buildDebianDir(appImage: Directory, debFileTree: Directory, debArch: String) {
        val dirDebian = debFileTree.dir("DEBIAN")
        dirDebian.asPath().createDirectories(asFileAttribute(posixExecutable))
        val fileControl = dirDebian.file("control")
        createControlFile(fileControl, appImage, debArch)
        linuxDebPreInst.copy(dirDebian.file("preinst"), posixExecutable)
        linuxDebPostInst.copy(dirDebian.file("postinst"), posixExecutable)
        linuxDebPreRm.copy(dirDebian.file("prerm"), posixExecutable)
        linuxDebPostRm.copy(dirDebian.file("postrm"), posixExecutable)
    }

    private fun RegularFileProperty.asPath(): Path {
        return get().asFile.toPath()
    }

    private fun RegularFile.asPath(): Path {
        return asFile.toPath()
    }

    private fun Directory.asPath(): Path {
        return asFile.toPath()
    }

    private fun RegularFileProperty.copy(target: RegularFile, permissions: Set<PosixFilePermission>) {
        if (ioFileOrNull == null) return
        target.asPath().parent.createDirectories(asFileAttribute(posixExecutable))
        Files.copy(asPath(), target.asPath())
        Files.setPosixFilePermissions(target.asPath(), permissions)
    }

    private fun createControlFile(fileControl: RegularFile, appImage: Directory, debArch: String) {
        // Determine installed size as in jdk.jpackage.internal.LinuxDebBundler#createReplacementData()
        val sizeInBytes = sizeInBytes(appImage.asFile.toPath())
        val installedSize = (sizeInBytes shr 10).toString()
        logger.lifecycle("size in bytes: $sizeInBytes")
        logger.lifecycle("installed size: $installedSize")

        // Determine package dependencies as in jdk.jpackage.internal.LibProvidersLookup and
        // jdk.jpackage.internal.LinuxDebBundler
        val packages = findPackageDependencies(debArch)

        logger.lifecycle("arch packages: ${packages.archPackages}")
        logger.lifecycle("other packages: ${packages.otherPackages}")
        val list = mutableListOf<String>().apply {
            addAll(packages.archPackages)
            addAll(packages.otherPackages)
        }
        // TODO: only add this if launcher is included (see jdk.jpackage.internal.DesktopIntegration)
        list.add("xdg-utils")
        linuxDebAdditionalDependencies.orNull?.let { list.addAll(it) }
        list.sort()

        fileControl.asFile.bufferedWriter().use { writer ->
            writer.writeLn("Package: ${linuxPackageName.get()}")
            writer.writeLn("Version: ${linuxDebPackageVersion.get()}-1")
            writer.writeLn("Section: ${linuxAppCategory.get()}")
            writer.writeLn("Maintainer: ${packageVendor.get()} <${linuxDebMaintainer.get()}>")
            writer.writeLn("Priority: optional")
            writer.writeLn("Architecture: amd64")
            writer.writeLn("Provides: ${linuxPackageName.get()}")
            writer.writeLn("Description: ${packageDescription.get()}")
            writer.writeLn("Depends: ${list.joinToString(", ")}")
            writer.writeLn("Installed-Size: $installedSize")
        }
    }

    data class FindPackageResults(val archPackages: Set<String>, val otherPackages: Set<String>)

    private fun findPackageDependencies(debArch: String): FindPackageResults {
        val set = mutableSetOf<Path>()
        for (file in appImage.asFileTree.filter { canDependOnLibs(it) }) {
            val resultLdd = runExternalToolAndGetOutput(
                tool = DebianUtils.ldd,
                args = listOf(file.toString())
            )
            resultLdd.stdout.lines().forEach { line ->
                val matcher = LIB_IN_LDD_OUTPUT_REGEX.matcher(line)
                if (matcher.find()) {
                    set.add(Paths.get(matcher.group(1)))
                }
            }
        }
        logger.lifecycle("lib files: $set")

        val archPackages = mutableSetOf<String>()
        val otherPackages = mutableSetOf<String>()

        for (path in set) {
            val resultDpkg = runExternalToolAndGetOutput(
                tool = DebianUtils.dpkg,
                args = listOf("-S", debFileTree.toString(), path.toString())
            )
            resultDpkg.stdout.lines().forEach { line ->
                val matcher: Matcher = PACKAGE_NAME_REGEX.matcher(line)
                if (matcher.find()) {
                    var name: String = matcher.group(1)
                    if (name.endsWith(":$debArch")) {
                        name = name.substring(0, name.length - (debArch.length + 1))
                        archPackages.add(name)
                    } else {
                        otherPackages.add(name)
                    }
                }
            }
        }

        return FindPackageResults(archPackages, otherPackages)
    }

    // Same algorithm as jdk.jpackage.internal.PathGroup.Facade#sizeInBytes()
    open fun sizeInBytes(dir: Path): Long {
        var sum: Long = 0
        Files.walk(dir).use { stream ->
            sum += stream.filter { p -> Files.isRegularFile(p) }
                .mapToLong { f -> f.toFile().length() }.sum()
        }
        return sum
    }

    private fun canDependOnLibs(file: File): Boolean {
        return file.canExecute() || file.toString().endsWith(".so")
    }

    private fun syncDir(source: Directory, target: Directory, takeFile: (file: Path) -> Boolean = { _ -> true }) {
        val pathSourceDir = source.asFile.toPath()
        val pathTargetDir = target.asFile.toPath()
        for (file in source.asFileTree.files) {
            val pathSource = file.toPath()
            val relative = pathSourceDir.relativize(pathSource)
            if (!takeFile(relative)) {
                continue
            }
            val pathTarget = pathTargetDir.resolve(relative)
            createDirectories(pathTarget.parent, asFileAttribute(posixExecutable))
            if (Files.isExecutable(pathSource)) {
                Files.setPosixFilePermissions(pathSource, posixExecutable)
            } else {
                Files.setPosixFilePermissions(pathSource, posixRegular)
            }
            Files.copy(pathSource, pathTarget)
        }
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
