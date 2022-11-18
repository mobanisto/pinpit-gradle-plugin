package de.mobanisto.compose.desktop.application.tasks

import de.mobanisto.compose.desktop.application.dsl.TargetFormat
import de.mobanisto.compose.desktop.application.internal.ComposeProperties
import de.mobanisto.compose.desktop.application.internal.notNullProperty
import de.mobanisto.compose.desktop.application.internal.nullableProperty
import de.mobanisto.compose.desktop.tasks.AbstractComposeDesktopTask
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.tools.ant.util.PermissionUtils
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecResult
import org.gradle.work.InputChanges
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.inputStream

abstract class CustomPackageTask @Inject constructor(
    @get:Input val targetFormat: TargetFormat,
) : AbstractComposeDesktopTask() {
    @get:LocalState
    protected val workingDir: Provider<Directory> = project.layout.buildDirectory.dir("mocompose/tmp/$name")

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    val jvmVendor: Property<String> = objects.notNullProperty()

    @get:Input
    val jvmVersion: Property<String> = objects.notNullProperty()

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

    @get:Input
    val launcherMainClass: Property<String> = objects.notNullProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val launcherMainJar: RegularFileProperty = objects.fileProperty()

    @get:Input
    val packageName: Property<String> = objects.notNullProperty()

    @get:Input
    val packageDescription: Property<String> = objects.notNullProperty()

    @get:Input
    @get:Optional
    val packageCopyright: Property<String?> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val packageVendor: Property<String?> = objects.nullableProperty()

    @get:InputDirectory
    @get:Optional
    val appImage: DirectoryProperty = objects.directoryProperty()

    @get:InputFile
    @get:Optional
    val javaRuntimePropertiesFile: RegularFileProperty = objects.fileProperty()

    protected open fun prepareWorkingDir(inputChanges: InputChanges) {
        cleanDirs(workingDir)
    }

    protected open fun initState() {}
    protected open fun createPackage() {}
    protected open fun saveStateAfterFinish() {}
    protected open fun checkResult(result: ExecResult) {}

    @TaskAction
    fun run(inputChanges: InputChanges) {
        initState()

        fileOperations.delete(destinationDir)
        prepareWorkingDir(inputChanges)

        try {
            createPackage()
        } finally {
            if (!ComposeProperties.preserveWorkingDir(providers).get()) {
                fileOperations.delete(workingDir)
            }
        }
        saveStateAfterFinish()
    }

    companion object {
        val osToExtension = mapOf(
            "linux" to "tar.gz",
            "windows" to "zip",
            "mac" to "tar.gz",
        )
    }

    /**
     * Download and extract configured JVM to ~/.hokkaido/jdks/$vendor/$version
     *
     * @param os linux, windows or mac
     * @param arch x64 or aarch64
     */
    protected fun downloadJdk(os: String, arch: String) {
        val dirHome = Paths.get(System.getProperty("user.home"))
        val dirTool = dirHome.resolve(".hokkaido")
        val dirJdks = dirTool.resolve("jdks")
        val extension = osToExtension[os] ?: throw GradleException("Invalid os: $os")
        val vendor = jvmVendor.get()
        if (vendor == "adoptium") {
            val match = "(\\d+).(\\d+).(\\d+)\\+(\\d+)".toRegex().matchEntire(jvmVersion.get())
                ?: throw GradleException("Invalid version")
            val (full, major, minor, patch, build) = match.groupValues
            val fileVersion = jvmVersion.get().replace("+", "_")
            val urlVersion = URLEncoder.encode(jvmVersion.get(), Charsets.UTF_8)
            val url = "https://github.com/adoptium/temurin$major-binaries/releases/download/" +
                    "jdk-$urlVersion/OpenJDK${major}U-jdk_${arch}_${os}_hotspot_$fileVersion.$extension"
            val nameFile = "OpenJDK${major}U-jdk_${arch}_${os}_hotspot_$fileVersion.$extension"
            val nameDir = "jdk-$major.$minor.$patch+$build"
            val dirVendor = dirJdks.resolve(vendor)
            val targetDir = dirVendor.resolve(nameDir)
            if (!targetDir.exists()) {
                Files.createDirectories(dirVendor)
                val targetFile = dirVendor.resolve(nameFile)
                if (!targetFile.exists()) {
                    logger.lifecycle("Downloading JDK from \"$url\" to $targetFile")
                    URL(url).openStream().use {
                        Files.copy(it, targetFile)
                    }
                }
                logger.lifecycle("Extracting to \"$targetDir\"")
                extractTarGz(targetFile, targetDir, nameDir)
            }
        }
    }

    private fun extractTarGz(targetFile: Path, targetDir: Path, nameDir: String) {
        TarArchiveInputStream(GZIPInputStream(targetFile.inputStream())).use {
            while (true) {
                val entry = it.nextEntry as TarArchiveEntry? ?: break
                val path = Paths.get(entry.name)
                if (!entry.isDirectory && path.startsWith(nameDir) && path.nameCount > 1) {
                    val file = targetDir.resolve(path.subpath(1, path.nameCount))
                    Files.createDirectories(file.parent)
                    Files.copy(it, file)
                    Files.setPosixFilePermissions(file, PermissionUtils.permissionsFromMode(entry.mode))
                }
            }
        }
    }
}
