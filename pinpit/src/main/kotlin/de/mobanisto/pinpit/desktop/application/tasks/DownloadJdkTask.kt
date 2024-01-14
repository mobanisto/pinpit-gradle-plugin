package de.mobanisto.pinpit.desktop.application.tasks

import de.mobanisto.pinpit.desktop.application.internal.adoptiumUrl
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.isUnix
import de.mobanisto.pinpit.desktop.application.internal.jdkInfo
import de.mobanisto.pinpit.desktop.application.internal.notNullProperty
import de.mobanisto.pinpit.desktop.tasks.AbstractPinpitTask
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.tools.ant.util.PermissionUtils
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.net.URL
import java.nio.file.Files
import java.nio.file.Files.createDirectories
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.inputStream

abstract class DownloadJdkTask @Inject constructor() : AbstractPinpitTask() {

    @Internal
    val jvmVendor: Property<String> = objects.notNullProperty()

    @Internal
    val jvmVersion: Property<String> = objects.notNullProperty()

    @Internal
    val os: Property<String> = objects.notNullProperty()

    @Internal
    val arch: Property<String> = objects.notNullProperty()

    @Internal
    var jdkDir: Path? = null

    @TaskAction
    fun run() {
        downloadJdk(os.get(), arch.get())
    }

    companion object {
        val osToExtension = mapOf(
            "linux" to "tar.gz",
            "windows" to "zip",
            "macos" to "tar.gz",
        )
    }

    /**
     * Download and extract configured JVM to ~/.pinpit/jdks/$vendor/$version
     *
     * @param os linux, windows or mac
     * @param arch x64 or aarch64
     */
    private fun downloadJdk(os: String, arch: String) {
        val dirHome = Paths.get(System.getProperty("user.home"))
        val dirTool = dirHome.resolve(".pinpit")
        val dirJdks = dirTool.resolve("jdks")
        val extension = osToExtension[os] ?: throw GradleException("Invalid os: $os")
        val vendor = jvmVendor.get()
        val info = jdkInfo(jvmVendor.get(), jvmVersion.get()) ?: return
        if (vendor == "adoptium") {
            val osSource = if (os == "macos") "mac" else os
            val fileVersion = jvmVersion.get().replace("+", "_")
            val url = adoptiumUrl(info, osSource, arch, jvmVersion.get(), fileVersion, extension)
            val nameFile = "OpenJDK${info.feature}U-jdk_${arch}_${os}_hotspot_$fileVersion.$extension"
            val nameDir = "OpenJDK${info.feature}U-jdk_${arch}_${os}_hotspot_$fileVersion"
            val nameDirContent = "jdk-${info.full}"
            val dirVendor = dirJdks.resolve(vendor).also { createDirectories(it) }
            val targetDir = dirVendor.resolve(nameDir)
            if (!targetDir.exists()) {
                val targetFile = dirVendor.resolve(nameFile)
                if (!targetFile.exists()) {
                    logger.lifecycle("Downloading JDK from \"$url\" to $targetFile")
                    URL(url).openStream().use {
                        Files.copy(it, targetFile)
                    }
                }
                logger.lifecycle("Extracting to \"$targetDir\"")
                if (extension == "tar.gz") {
                    extractTarGz(targetFile, targetDir, nameDirContent)
                } else if (extension == "zip") {
                    extractZip(targetFile, targetDir, nameDirContent)
                }
            }
            jdkDir = targetDir
        }
    }

    /**
     * Extract contents below [nameDir] from within archive [targetFile] to [targetDir].
     */
    private fun extractTarGz(targetFile: Path, targetDir: Path, nameDir: String) {
        TarArchiveInputStream(GZIPInputStream(targetFile.inputStream())).use {
            while (true) {
                val entry = it.nextEntry as TarArchiveEntry? ?: break
                val path = Paths.get(entry.name)
                if (!entry.isDirectory && path.startsWith(nameDir) && path.nameCount > 1) {
                    val file = targetDir.resolve(path.subpath(1, path.nameCount))
                    createDirectories(file.parent)
                    Files.copy(it, file)
                    if (currentOS.isUnix()) {
                        Files.setPosixFilePermissions(file, PermissionUtils.permissionsFromMode(entry.mode))
                    }
                }
            }
        }
    }

    /**
     * Extract contents below [nameDir] from within archive [targetFile] to [targetDir].
     */
    private fun extractZip(targetFile: Path, targetDir: Path, nameDir: String) {
        ZipArchiveInputStream(targetFile.inputStream()).use {
            while (true) {
                val entry: ZipArchiveEntry = it.nextEntry as ZipArchiveEntry? ?: break
                val path = Paths.get(entry.name)
                if (!entry.isDirectory && path.startsWith(nameDir) && path.nameCount > 1) {
                    val file = targetDir.resolve(path.subpath(1, path.nameCount))
                    createDirectories(file.parent)
                    Files.copy(it, file)
                    // Files.setPosixFilePermissions(file, PermissionUtils.permissionsFromMode(entry.unixMode))
                }
            }
        }
    }
}
