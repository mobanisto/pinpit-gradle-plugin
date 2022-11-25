package de.mobanisto.compose.desktop.application.tasks

import de.mobanisto.compose.desktop.application.internal.Arch
import de.mobanisto.compose.desktop.application.internal.OS
import de.mobanisto.compose.desktop.application.internal.Target
import de.mobanisto.compose.desktop.application.internal.currentArch
import de.mobanisto.compose.desktop.application.internal.currentOS
import de.mobanisto.compose.desktop.application.internal.files.copyTo
import de.mobanisto.compose.desktop.application.internal.files.copyZipEntry
import de.mobanisto.compose.desktop.application.internal.files.transformJar
import org.gradle.api.internal.file.FileOperations
import java.io.File

internal fun isSkikoForCurrentOS(lib: File): Boolean =
    isSkikoFor(currentOS, currentArch, lib)

internal fun isSkikoFor(target: Target, lib: File): Boolean =
    isSkikoFor(target.os, target.arch, lib)

internal fun isSkikoFor(os: OS, arch: Arch, lib: File): Boolean =
    lib.name.startsWith("skiko-awt-runtime-${os.id}-${arch.id}")
            && lib.name.endsWith(".jar")

internal fun unpackSkikoForCurrentOS(
    sourceJar: File, skikoDir: File, fileOperations: FileOperations
): List<File> {
    return unpackSkikoFor(currentOS, currentArch, sourceJar, skikoDir, fileOperations)
}

internal fun unpackSkikoFor(
    target: Target, sourceJar: File, skikoDir: File, fileOperations: FileOperations
): List<File> {
    return unpackSkikoFor(target.os, target.arch, sourceJar, skikoDir, fileOperations)
}

internal fun unpackSkikoFor(
    os: OS, arch: Arch, sourceJar: File, skikoDir: File, fileOperations: FileOperations
): List<File> {
    val entriesToUnpack = when (os) {
        OS.MacOS -> setOf("libskiko-macos-${arch.id}.dylib")
        OS.Windows -> setOf("skiko-windows-${arch.id}.dll", "icudtl.dat")
        OS.Linux -> setOf("libskiko-linux-${arch.id}.so")
    }

    // output files: unpacked libs, corresponding .sha256 files, and target jar
    val outputFiles = ArrayList<File>(entriesToUnpack.size * 2 + 1)
    val targetJar = skikoDir.resolve(sourceJar.name)
    outputFiles.add(targetJar)

    fileOperations.delete(skikoDir)
    fileOperations.mkdir(skikoDir)
    transformJar(sourceJar, targetJar) { entry, zin, zout ->
        // check both entry or entry.sha256
        if (entry.name.removeSuffix(".sha256") in entriesToUnpack) {
            val unpackedFile = skikoDir.resolve(entry.name.substringAfterLast("/"))
            zin.copyTo(unpackedFile)
            outputFiles.add(unpackedFile)
        } else {
            copyZipEntry(entry, zin, zout)
        }
    }
    return outputFiles
}

