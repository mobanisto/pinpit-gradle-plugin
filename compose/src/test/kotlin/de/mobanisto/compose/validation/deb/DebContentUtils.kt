package de.mobanisto.compose.validation.deb

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
}
