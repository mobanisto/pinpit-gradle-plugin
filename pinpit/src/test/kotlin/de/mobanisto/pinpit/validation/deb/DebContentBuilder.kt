/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.validation.deb

import de.mobanisto.pinpit.desktop.application.internal.files.isProbablyNotBinary
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.CountingInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class DebContent(val arEntries: List<ArEntry>, val tars: Map<String, Tar>)
data class Tar(val name: String, val entries: List<TarEntry>)
interface ArchiveEntry {
    val name: String
}

data class ArEntry(
    override val name: String, val size: Long, val user: Long, val group: Long, val mode: Int, val hash: String
) :
    ArchiveEntry

data class TarEntry(
    override val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val user: Long,
    val group: Long,
    val mode: Int,
    val hash: String
) : ArchiveEntry {
    override fun toString() =
        "TarEntry(name=$name, size=$size, user=$user, group=$group, mode=${Integer.toOctalString(mode)}, hash=$hash)"
}

data class DebAddress(val tar: String, val path: String)

class DebContentBuilder {

    fun buildContent(file: File): DebContent {
        return file.inputStream().use { fis ->
            buildContent(fis)
        }
    }

    fun buildContent(fis: InputStream): DebContent {
        val arEntries = mutableListOf<ArEntry>()
        val tars = mutableMapOf<String, Tar>()

        val ais = ArArchiveInputStream(fis)
        while (true) {
            val entry1: ArArchiveEntry = ais.nextArEntry ?: break
            val name1 = entry1.name

            if (name1.endsWith("tar.xz")) {
                val tarEntries = mutableListOf<TarEntry>()
                tars[name1] = Tar(name1, tarEntries)
                val xz = XZCompressorInputStream(ais)
                val tis = TarArchiveInputStream(xz)
                while (true) {
                    val entry2 = tis.nextTarEntry ?: break
                    val name = entry2.name
                    if (entry2.isDirectory) {
                        tarEntries.add(TarEntry(name, true, 0, entry2.longUserId, entry2.longGroupId, entry2.mode, ""))
                    } else {
                        val counter = CountingInputStream(tis)
                        val hash = DigestUtils.sha1Hex(counter)
                        tarEntries.add(
                            TarEntry(
                                name,
                                false,
                                counter.bytesRead,
                                entry2.longUserId,
                                entry2.longGroupId,
                                entry2.mode,
                                hash
                            )
                        )
                    }
                }
            } else {
                val counter = CountingInputStream(ais)
                val hash = DigestUtils.sha1Hex(counter)
                arEntries.add(
                    ArEntry(
                        name1,
                        counter.bytesRead,
                        entry1.userId.toLong(),
                        entry1.groupId.toLong(),
                        entry1.mode,
                        hash
                    )
                )
            }
        }

        return DebContent(arEntries, tars)
    }

    private fun accept(name: String, tar: TarComparisonResult): Boolean {
        for (entry in tar.onlyIn1) {
            if (entry.name == name) {
                return true
            }
        }
        for (entry in tar.onlyIn2) {
            if (entry.name == name) {
                return true
            }
        }
        for (entry in tar.different) {
            if (entry.first.name == name) {
                return true
            }
        }
        return false
    }

    fun buildContentForComparison(
        fis: InputStream,
        comparison: Map<String, TarComparisonResult>
    ): Map<DebAddress, ByteArray> {
        val ais = ArArchiveInputStream(fis)

        val map = mutableMapOf<DebAddress, ByteArray>()
        while (true) {
            val entry1: ArArchiveEntry = ais.nextArEntry ?: break
            val name1 = entry1.name

            if (name1.endsWith("tar.xz")) {
                val tar = comparison[name1] ?: continue
                val xz = XZCompressorInputStream(ais)
                val tis = TarArchiveInputStream(xz)
                while (true) {
                    val entry2 = tis.nextTarEntry ?: break
                    if (entry2.isDirectory) continue
                    val name2 = entry2.name
                    if (accept(name2, tar)) {
                        val bytes = tis.readBytes()
                        if (bytes.isProbablyNotBinary()) {
                            map[DebAddress(name1, name2)] = bytes
                        } else {
                            println("not collecting data for binary file $name2")
                        }
                    }
                }
            }
        }

        return map
    }

    fun getControl(packageFile: File): ByteArray? {
        return getFile(packageFile, "control.tar.xz", "./control")
    }

    private fun getFile(packageFile: File, tarFile: String, path: String): ByteArray? {
        FileInputStream(packageFile).use { fis ->
            ArArchiveInputStream(fis).use { ais ->

                while (true) {
                    val entry1: ArArchiveEntry = ais.nextArEntry ?: break
                    val name1 = entry1.name

                    if (name1 == tarFile) {
                        val xz = XZCompressorInputStream(ais)
                        val tis = TarArchiveInputStream(xz)
                        while (true) {
                            val entry2 = tis.nextTarEntry ?: break
                            if (entry2.isDirectory) continue
                            val name2 = entry2.name
                            if (name2 == path) {
                                return tis.readBytes()
                            }
                        }
                    }
                }
                return null
            }
        }
    }
}
