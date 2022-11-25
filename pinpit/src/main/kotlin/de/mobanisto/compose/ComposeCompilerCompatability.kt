package de.mobanisto.compose

import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

internal object ComposeCompilerCompatability {
    fun compilerVersionFor(kotlinVersion: String): de.mobanisto.compose.ComposeCompilerVersion? = when (kotlinVersion) {
        "1.7.10" -> de.mobanisto.compose.ComposeCompilerVersion("1.3.0")
        "1.7.20" -> de.mobanisto.compose.ComposeCompilerVersion("1.3.2.1")
        else -> null
    }
}

internal data class ComposeCompilerVersion(
    val version: String,
    val unsupportedPlatforms: Set<KotlinPlatformType> = emptySet()
)
