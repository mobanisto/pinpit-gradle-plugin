/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat
import de.mobanisto.pinpit.desktop.application.internal.DebianUtils
import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.dir
import de.mobanisto.pinpit.desktop.application.internal.files.SimpleFileCopyingProcessor
import de.mobanisto.pinpit.desktop.application.internal.files.asPath
import de.mobanisto.pinpit.desktop.application.internal.files.findOutputFileOrDir
import de.mobanisto.pinpit.desktop.application.internal.files.isJarFile
import de.mobanisto.pinpit.desktop.application.internal.files.mangledName
import de.mobanisto.pinpit.desktop.application.internal.files.posixExecutable
import de.mobanisto.pinpit.desktop.application.internal.files.posixRegular
import de.mobanisto.pinpit.desktop.application.internal.files.syncDir
import de.mobanisto.pinpit.desktop.application.internal.files.writeLn
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
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute
import javax.inject.Inject
import kotlin.io.path.createDirectories


abstract class PackageDebTask @Inject constructor(
    target: Target,
    @Input val qualifier: String,
) : CustomPackageTask(target, TargetFormat.CustomDeb) {

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

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebCopyright: RegularFileProperty = objects.fileProperty()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val linuxDebLauncher: RegularFileProperty = objects.fileProperty()

    @get:Input
    @get:Optional
    val depends: ListProperty<String> = objects.listProperty(String::class.java)

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

    @get:Internal
    private val debFileTree: Provider<Directory> = workingDir.dir("debFileTree")

    override fun createPackage() {
        val destination = destinationDir.get()
        logger.lifecycle("destination: $destination")
        destination.asFile.mkdirs()

        val appImage = appImage.get().dir(packageName).get()
        logger.lifecycle("app image: $appImage")

        val debFileTree = debFileTree.get()
        logger.lifecycle("building debian file tree at: $debFileTree")
        debFileTree.asFile.mkdirs()

        fileOperations.delete(debFileTree)
        buildDebFileTree(appImage, debFileTree)
        buildDebianDir(appImage, debFileTree)

        val deb =
            destination.file("${linuxPackageName.get()}-$qualifier-${target.arch.id}-${linuxDebPackageVersion.get()}.deb")
        runExternalTool(
            tool = DebianUtils.fakeroot,
            args = listOf(DebianUtils.dpkgDeb.toString(), "-b", debFileTree.toString(), deb.toString())
        )
    }

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

    private fun buildDebianDir(appImage: Directory, debFileTree: Directory) {
        val dirDebian = debFileTree.dir("DEBIAN")
        dirDebian.asPath().createDirectories(asFileAttribute(posixExecutable))
        val fileControl = dirDebian.file("control")
        createControlFile(fileControl, appImage)
        linuxDebPreInst.copy(dirDebian.file("preinst"), posixExecutable)
        linuxDebPostInst.copy(dirDebian.file("postinst"), posixExecutable)
        linuxDebPreRm.copy(dirDebian.file("prerm"), posixExecutable)
        linuxDebPostRm.copy(dirDebian.file("postrm"), posixExecutable)
    }

    private fun RegularFileProperty.copy(target: RegularFile, permissions: Set<PosixFilePermission>) {
        if (ioFileOrNull == null) return
        target.asPath().parent.createDirectories(asFileAttribute(posixExecutable))
        Files.copy(asPath(), target.asPath())
        Files.setPosixFilePermissions(target.asPath(), permissions)
    }

    private fun createControlFile(fileControl: RegularFile, appImage: Directory) {
        // Determine installed size as in jdk.jpackage.internal.LinuxDebBundler#createReplacementData()
        val sizeInBytes = sizeInBytes(appImage.asFile.toPath())
        val installedSize = (sizeInBytes shr 10).toString()
        logger.lifecycle("size in bytes: $sizeInBytes")
        logger.lifecycle("installed size: $installedSize")

        val list = mutableListOf<String>().apply {
            addAll(depends.get())
        }
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

    // Same algorithm as jdk.jpackage.internal.PathGroup.Facade#sizeInBytes()
    open fun sizeInBytes(dir: Path): Long {
        var sum: Long = 0
        Files.walk(dir).use { stream ->
            sum += stream.filter { p -> Files.isRegularFile(p) }
                .mapToLong { f -> f.toFile().length() }.sum()
        }
        return sum
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
