package de.mobanisto.compose.validation.deb

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.CountingInputStream
import java.io.InputStream

data class DebContent(val arEntries: Map<String, ArEntry>, val tars: Map<String, Tar>)
data class Tar(val name: String, val entries: List<TarEntry>)
data class ArEntry(val name: String, val size: Long, val user: Long, val group: Long, val hash: String)
data class TarEntry(val name: String, val size: Long, val user: Long, val group: Long, val hash: String)

class DebContentBuilder {

    fun buildContent(fis: InputStream): DebContent {
        val arEntries = mutableMapOf<String, ArEntry>()
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
                    if (entry2.isDirectory) continue
                    val name2 = entry2.name
                    val counter = CountingInputStream(tis)
                    val hash = DigestUtils.sha1Hex(counter)
                    tarEntries.add(TarEntry(name2, counter.bytesRead, entry2.longUserId, entry2.longGroupId, hash))
                }
            } else {
                val counter = CountingInputStream(ais)
                val hash = DigestUtils.sha1Hex(counter)
                arEntries[name1] =
                    ArEntry(name1, counter.bytesRead, entry1.userId.toLong(), entry1.groupId.toLong(), hash)
            }
        }

        return DebContent(arEntries, tars)
    }
}
