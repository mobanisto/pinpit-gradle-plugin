/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.application.internal.files.normalizedPath
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.Path

internal fun <T : Any?> MutableCollection<String>.cliArg(
    name: String,
    value: T?,
    fn: (T) -> String = defaultToString()
) {
    if (value is Boolean) {
        if (value) add(name)
    } else if (value != null) {
        add(name)
        add(fn(value))
    }
}

internal fun <T : Any?> MutableCollection<String>.cliArg(
    name: String,
    value: Provider<T>,
    fn: (T) -> String = defaultToString()
) {
    cliArg(name, value.orNull, fn)
}

internal fun MutableCollection<String>.javaOption(value: String) {
    cliArg("--java-options", "'$value'")
}

private fun <T : Any?> defaultToString(): (T) -> String =
    {
        val asString = when (it) {
            is FileSystemLocation -> it.asFile.normalizedPath()
            is File -> it.normalizedPath()
            is Path -> it.toFile().normalizedPath()
            else -> it.toString()
        }
        "\"$asString\""
    }
