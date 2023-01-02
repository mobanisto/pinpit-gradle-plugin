/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.validation.deb

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.algorithm.myers.MeyersDiff
import java.io.File

data class ComparisonResult(
    val arComparisonResult: ArComparisonResult,
    val tarComparisonResult: Map<String, TarComparisonResult>,
)

data class ArComparisonResult(
    val onlyIn1: List<ArEntry>, val onlyIn2: List<ArEntry>, val different: List<Pair<ArEntry, ArEntry>>
)

data class TarComparisonResult(
    val onlyIn1: List<TarEntry>, val onlyIn2: List<TarEntry>, val different: List<Pair<TarEntry, TarEntry>>
)

object DebContentUtils {
    fun compare(deb1: DebContent, deb2: DebContent): ComparisonResult {
        val map = mutableMapOf<String, TarComparisonResult>()
        for (file in listOf("control.tar.xz", "data.tar.xz")) {
            val data1 = deb1.tars[file]
            val data2 = deb2.tars[file]
            checkNotNull(data1)
            checkNotNull(data2)
            val map1 = data1.entries.associateBy({ it.name }, { it })
            val map2 = data2.entries.associateBy({ it.name }, { it })
            val onlyIn1 = findOnlyInFirst(data1.entries, map2)
            val onlyIn2 = findOnlyInFirst(data2.entries, map1)
            val different = findDifferent(data1.entries, map2)
            map[file] = TarComparisonResult(onlyIn1, onlyIn2, different)
        }
        val map1 = deb1.arEntries.associateBy({ it.name }, { it })
        val map2 = deb2.arEntries.associateBy({ it.name }, { it })
        val onlyIn1 = findOnlyInFirst(deb1.arEntries, map2)
        val onlyIn2 = findOnlyInFirst(deb2.arEntries, map1)
        val different = findDifferent(deb1.arEntries, map2)
        val arComparison = ArComparisonResult(onlyIn1, onlyIn2, different)
        return ComparisonResult(arComparison, map)
    }

    private fun <T: ArchiveEntry> findOnlyInFirst(entries: List<T>, map: Map<String, T>): List<T> {
        val only = mutableListOf<T>()
        for (entry in entries) {
            if (map[entry.name] == null) {
                only.add(entry)
            }
        }
        return only
    }

    private fun <T : ArchiveEntry> findDifferent(entries: List<T>, map: Map<String, T>): List<Pair<T, T>> {
        val diff = mutableListOf<Pair<T, T>>()
        for (entry in entries) {
            val otherEntry = map[entry.name] ?: continue
            if (entry != otherEntry) {
                diff.add(entry to otherEntry)
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
                val address = DebAddress(tarFile, entry.first.name)
                val bytes1 = content1[address]
                val bytes2 = content2[address]
                if (bytes1 != null && bytes2 != null) {
                    printDiff(tarFile, entry.first, String(bytes1), String(bytes2))
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
