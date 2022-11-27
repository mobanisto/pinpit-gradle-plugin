/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

// Serializable is only needed to avoid breaking configuration cache:
// https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements
internal class FilesMapping : Serializable {
    private var mapping = HashMap<File, List<File>>()

    operator fun get(key: File): List<File>? =
        mapping[key]

    operator fun set(key: File, value: List<File>) {
        mapping[key] = value
    }

    fun remove(key: File): List<File>? =
        mapping.remove(key)

    fun loadFrom(mappingFile: File) {
        mappingFile.readLines().forEach { line ->
            if (line.isNotBlank()) {
                val paths = line.splitToSequence(File.pathSeparatorChar)
                val lib = File(paths.first())
                val mappedFiles = paths.drop(1).mapTo(ArrayList()) { File(it) }
                mapping[lib] = mappedFiles
            }
        }
    }

    fun saveTo(mappingFile: File) {
        mappingFile.parentFile.mkdirs()
        mappingFile.bufferedWriter().use { writer ->
            mapping.entries
                .sortedBy { (k, _) -> k.absolutePath }
                .forEach { (k, values) ->
                    (sequenceOf(k) + values.asSequence())
                        .joinTo(writer, separator = File.pathSeparator, transform = { it.absolutePath })
                }
        }
    }

    private fun writeObject(stream: ObjectOutputStream) {
        stream.writeObject(mapping)
    }

    private fun readObject(stream: ObjectInputStream) {
        mapping = stream.readObject() as HashMap<File, List<File>>
    }
}

