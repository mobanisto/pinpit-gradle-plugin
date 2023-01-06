/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal.files

import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat
import de.mobanisto.pinpit.desktop.application.internal.FileVisitorBuilder
import de.mobanisto.pinpit.desktop.application.internal.FileVisitorBuilderImpl
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.isUnix
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.Writer
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal fun File.mangledName(): String =
    buildString {
        append(nameWithoutExtension)
        append("-")
        append(contentHash())
        val ext = extension
        if (ext.isNotBlank()) {
            append(".$ext")
        }
    }

internal fun File.contentHash(): String {
    val md5 = MessageDigest.getInstance("MD5")
    if (isDirectory) {
        walk()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(this).path }
            .forEach { md5.digestContent(it) }
    } else {
        md5.digestContent(this)
    }
    val digest = md5.digest()
    return buildString(digest.size * 2) {
        for (byte in digest) {
            append(Integer.toHexString(0xFF and byte.toInt()))
        }
    }
}

private fun MessageDigest.digestContent(file: File) {
    file.inputStream().buffered().use { fis ->
        DigestInputStream(fis, this).use { ds ->
            while (ds.read() != -1) {
            }
        }
    }
}

internal inline fun transformJar(
    sourceJar: File,
    targetJar: File,
    fn: (entry: ZipEntry, zin: ZipInputStream, zout: ZipOutputStream) -> Unit
) {
    ZipInputStream(FileInputStream(sourceJar).buffered()).use { zin ->
        ZipOutputStream(FileOutputStream(targetJar).buffered()).use { zout ->
            for (sourceEntry in generateSequence { zin.nextEntry }) {
                fn(sourceEntry, zin, zout)
            }
        }
    }
}

internal fun copyZipEntry(
    entry: ZipEntry,
    from: InputStream,
    to: ZipOutputStream,
) {
    val newEntry = ZipEntry(entry.name).apply {
        comment = entry.comment
        extra = entry.extra
        lastModifiedTime = entry.lastModifiedTime
    }
    to.withNewEntry(newEntry) {
        from.copyTo(to)
    }
}

internal inline fun ZipOutputStream.withNewEntry(zipEntry: ZipEntry, fn: () -> Unit) {
    putNextEntry(zipEntry)
    fn()
    closeEntry()
}

internal fun InputStream.copyTo(file: File) {
    file.outputStream().buffered().use { os ->
        copyTo(os)
    }
}

@Internal
internal fun findOutputFileOrDir(dir: File, targetFormat: TargetFormat): File =
    when (targetFormat) {
        is TargetFormat.DistributableApp -> dir
        else -> dir.walk().first { it.isFile && it.name.endsWith(targetFormat.fileExt) }
    }

internal fun File.checkExistingFile(): File =
    apply {
        check(isFile) { "'$absolutePath' does not exist" }
    }

internal val File.isJarFile: Boolean
    get() = name.endsWith(".jar", ignoreCase = true) && isFile

internal fun File.normalizedPath() =
    if (currentOS == Windows) absolutePath.replace("\\", "\\\\") else absolutePath

internal fun Writer.writeLn(s: String? = null) {
    s?.let { write(it) }
    write("\n")
}

internal fun ByteArray.isProbablyNotBinary(): Boolean {
    var printable = 0
    var nonPrintable = 0
    for (byte in this) {
        if (byte in 32..126) {
            printable++
        } else {
            nonPrintable++
        }
        if (printable + nonPrintable >= 100) break
    }
    return printable / (printable + nonPrintable).toDouble() > 0.8
}

internal fun DirectoryProperty.asPath(): Path = get().asPath()

internal fun Provider<Directory>.asPath(): Path = get().asPath()

internal fun RegularFileProperty.asPath(): Path = get().asPath()

internal fun RegularFile.asPath(): Path = asFile.toPath()

internal fun Directory.asPath(): Path = asFile.toPath()

private const val permissionsRegular = "rw-r--r--"
private const val permissionsExecutable = "rwxr-xr-x"
internal val posixRegular = PosixFilePermissions.fromString(permissionsRegular)
internal val posixExecutable = PosixFilePermissions.fromString(permissionsExecutable)

internal fun syncDir(source: Directory, target: Directory, takeFile: (file: Path) -> Boolean = { _ -> true }) {
    val pathSourceDir = source.asFile.toPath()
    val pathTargetDir = target.asFile.toPath()
    syncDir(pathSourceDir, pathTargetDir, takeFile)
}

internal fun syncDir(source: Path, target: Path, takeFile: (file: Path) -> Boolean = { _ -> true }) {
    Files.walkFileTree(
        source,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = source.relativize(file)
                if (!takeFile(relative)) {
                    return FileVisitResult.CONTINUE
                }
                val pathTarget = target.resolve(relative)
                if (currentOS.isUnix()) {
                    Files.createDirectories(pathTarget.parent, PosixFilePermissions.asFileAttribute(posixExecutable))
                } else {
                    Files.createDirectories(pathTarget.parent)
                }
                if (currentOS.isUnix()) {
                    if (Files.isExecutable(file)) {
                        Files.setPosixFilePermissions(file, posixExecutable)
                    } else {
                        Files.setPosixFilePermissions(file, posixRegular)
                    }
                }
                Files.copy(file, pathTarget)
                return FileVisitResult.CONTINUE
            }
        }
    )
}

internal fun findRelative(source: Path, takeFile: (file: Path) -> Boolean = { _ -> true }): MutableList<Path> {
    val results = mutableListOf<Path>()
    Files.walkFileTree(
        source,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = source.relativize(file)
                if (!takeFile(file)) {
                    return FileVisitResult.CONTINUE
                }
                results.add(relative)
                return FileVisitResult.CONTINUE
            }
        }
    )
    return results
}

// TODO: remove once upgraded to kotlin 1.7.X
@OptIn(ExperimentalContracts::class)
public fun Path.visitFileTree(
    maxDepth: Int = Int.MAX_VALUE,
    followLinks: Boolean = false,
    builderAction: FileVisitorBuilder.() -> Unit
) {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    visitFileTree(fileVisitor(builderAction), maxDepth, followLinks)
}

// TODO: remove once upgraded to kotlin 1.7.X
internal fun Path.visitFileTree(
    visitor: FileVisitor<Path>,
    maxDepth: Int = Int.MAX_VALUE,
    followLinks: Boolean = false
) {
    val options = if (followLinks) setOf(FileVisitOption.FOLLOW_LINKS) else setOf()
    Files.walkFileTree(this, options, maxDepth, visitor)
}

// TODO: remove once upgraded to kotlin 1.7.X
@OptIn(ExperimentalContracts::class)
public fun fileVisitor(builderAction: FileVisitorBuilder.() -> Unit): FileVisitor<Path> {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    return FileVisitorBuilderImpl().apply(builderAction).build()
}
