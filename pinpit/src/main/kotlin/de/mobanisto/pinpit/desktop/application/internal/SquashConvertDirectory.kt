/*
 * Copyright 2025 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.topobyte.squashfs.SquashFsWriter
import de.topobyte.squashfs.compression.Compression
import de.topobyte.squashfs.util.PosixUtil.getPosixPermissionsAsInt
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.Integer.toOctalString
import java.nio.file.Files
import java.nio.file.Files.getAttribute
import java.nio.file.Files.getLastModifiedTime
import java.nio.file.Files.isDirectory
import java.nio.file.Files.isRegularFile
import java.nio.file.Files.newInputStream
import java.nio.file.Files.readAttributes
import java.nio.file.Files.readSymbolicLink
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * A modified version of SquashConvertDirectory from squashfs-tools
 * that supports trailing lambda for modifying the resulting image.
 * We can use that to create an additional symlink.
 */
class SquashConvertDirectory {

    private val logger: Logger = LoggerFactory.getLogger(SquashConvertDirectory::class.java)

    @Throws(IOException::class)
    fun convertToSquashFs(
        inputFile: Path, outputFile: Path,
        compression: Compression?, offset: Int,
        additionalFiles: (
            writer: SquashFsWriter,
            modDate: AtomicReference<Instant>
        ) -> Int
    ) {
        logger.info(
            "Converting {} -> {}...",
            inputFile.toAbsolutePath(),
            outputFile.toAbsolutePath()
        )
        Files.deleteIfExists(outputFile)
        var fileCount = 0L
        SquashFsWriter(outputFile.toFile(), compression, offset).use { writer ->
            val modDate = AtomicReference(
                Instant.ofEpochMilli(0)
            )
            fileCount = walk(inputFile, inputFile, 0, writer, modDate).toLong()
            fileCount += additionalFiles(writer, modDate)
            writer.setModificationTime(modDate.get().epochSecond.toInt())
            writer.finish()
        }
        logger.info("Converted image containing {} files.", fileCount)
    }

    @Throws(IOException::class)
    private fun walk(
        root: Path, path: Path, depth: Int, writer: SquashFsWriter,
        modDate: AtomicReference<Instant>
    ): Int {
        var count = 0
        Files.newDirectoryStream(path).use { stream ->
            for (file in stream) {
                if (isDirectory(file)) {
                    processFile(root, file, writer, modDate)
                    count += walk(root, file, depth + 1, writer, modDate)
                } else {
                    processFile(root, file, writer, modDate)
                    count++
                }
            }
        }
        return count
    }

    @Throws(IOException::class)
    private fun processFile(
        root: Path, file: Path, writer: SquashFsWriter,
        modDate: AtomicReference<Instant>
    ) {
        val posix = readAttributes(file, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val userId = getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
        val groupId = getAttribute(file, "unix:gid", LinkOption.NOFOLLOW_LINKS) as Int
        val relative = root.relativize(file)
        val name = relative.toString()
            .replace("/+".toRegex(), "/")
            .replace("^/".toRegex(), "")
            .replace("/$".toRegex(), "")
            .replace("^".toRegex(), "/")
        val permissions = (getPosixPermissionsAsInt(posix.permissions()) and "777".toInt(radix = 8)).toShort()
        logger.info(toOctalString(permissions.toInt()) + " " + name)
        val lastModified = getLastModifiedTime(file).toInstant()
        if (lastModified.isAfter(modDate.get())) {
            modDate.set(lastModified)
        }
        val tb = writer.entry(name).uid(userId).gid(groupId)
            .permissions(permissions).fileSize(Files.size(file))
            .lastModified(lastModified)
        if (Files.isSymbolicLink(file)) {
            val symlink = readSymbolicLink(file)
            logger.info("symlink: $symlink")
            tb.symlink(symlink.toString())
        } else if (isDirectory(file)) {
            logger.info("dir: $file")
            tb.directory()
        } else if (isRegularFile(file)) {
            tb.file()
        } else {
            throw IOException(String.format("Unknown file type for '%s'", file.fileName))
        }
        if (isRegularFile(file)) {
            tb.content(newInputStream(file), Files.size(file))
        }
        tb.build()
    }
}

