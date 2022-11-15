package de.mobanisto.compose.validation.deb

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.algorithm.myers.MeyersDiff
import java.io.File

data class TarComparisonResult(val onlyIn1: List<TarEntry>, val onlyIn2: List<TarEntry>, val different: List<TarEntry>)

object DebContentUtils {
    fun compare(deb1: DebContent, deb2: DebContent): Map<String, TarComparisonResult> {
        val map = mutableMapOf<String, TarComparisonResult>()
        for (file in listOf("control.tar.xz", "data.tar.xz")) {
            val data1 = deb1.tars[file]
            val data2 = deb2.tars[file]
            checkNotNull(data1)
            checkNotNull(data2)
            val map1 = data1.entries.associateBy({ it.name }, { it })
            val map2 = data2.entries.associateBy({ it.name }, { it })
            val onlyIn1 = findOnlyInFirst(data1, map2)
            val onlyIn2 = findOnlyInFirst(data2, map1)
            val different = findDifferent(data1, map2)
            map[file] = TarComparisonResult(onlyIn1, onlyIn2, different)
        }
        return map
    }

    private fun findOnlyInFirst(data: Tar, map: Map<String, TarEntry>): List<TarEntry> {
        val only = mutableListOf<TarEntry>()
        for (entry in data.entries) {
            if (map[entry.name] == null) {
                only.add(entry)
            }
        }
        return only
    }

    private fun findDifferent(data: Tar, map: Map<String, TarEntry>): List<TarEntry> {
        val diff = mutableListOf<TarEntry>()
        for (entry in data.entries) {
            val otherEntry = map[entry.name] ?: continue
            if (entry != otherEntry) {
                diff.add(entry)
            }
        }
        return diff
    }

    fun printDiff(deb1: File, deb2: File, comparison: Map<String, TarComparisonResult>) {
        val content1 = DebContentBuilder().buildContentForComparison(deb1.inputStream(), comparison)
        val content2 = DebContentBuilder().buildContentForComparison(deb2.inputStream(), comparison)
        for (tarEntry in comparison.entries) {
            val tarFile = tarEntry.key
            for (entry in tarEntry.value.different) {
                val address = DebAddress(tarFile, entry.name)
                val bytes1 = content1[address]
                val bytes2 = content2[address]
                if (bytes1 != null && bytes2 != null) {
                    printDiff(tarFile, entry, String(bytes1), String(bytes2))
                }
            }
        }
    }

    private fun printDiff(tarFile: String, entry: TarEntry, text1: String, text2: String) {
        // Print original texts
        println("$tarFile:${entry.name}:stock:")
        println(text1)
        println("$tarFile:${entry.name}:custom:")
        println(text2)
        // Compute diff and print
        val lines1 = text1.lines()
        val lines2 = text2.lines()
        val diff = DiffUtils.diff(lines1, lines2, MeyersDiff())
        val unified = UnifiedDiffUtils.generateUnifiedDiff("stock deb", "custom deb", lines1, diff, 1)
        for (line in unified) {
            println(line)
        }
    }
}
