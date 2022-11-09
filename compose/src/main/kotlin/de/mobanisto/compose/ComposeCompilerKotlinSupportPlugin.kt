/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import de.mobanisto.compose.internal.ComposeCompilerArtifactProvider
import org.jetbrains.kotlin.gradle.plugin.*

class ComposeCompilerKotlinSupportPlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var composeCompilerArtifactProvider: ComposeCompilerArtifactProvider

    override fun apply(target: Project) {
        super.apply(target)
        target.plugins.withType(de.mobanisto.compose.ComposePlugin::class.java) {
            val composeExt = target.extensions.getByType(de.mobanisto.compose.ComposeExtension::class.java)

            composeCompilerArtifactProvider = ComposeCompilerArtifactProvider(
                kotlinVersion = target.getKotlinPluginVersion()
            ) {
                composeExt.kotlinCompilerPlugin.orNull
            }
        }
    }

    override fun getCompilerPluginId(): String =
        "androidx.compose.compiler.plugins.kotlin"

    override fun getPluginArtifact(): SubpluginArtifact =
        composeCompilerArtifactProvider.compilerArtifact

    override fun getPluginArtifactForNative(): SubpluginArtifact =
        composeCompilerArtifactProvider.compilerHostedArtifact

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        when (kotlinCompilation.target.platformType) {
            KotlinPlatformType.common -> true
            KotlinPlatformType.jvm -> true
            KotlinPlatformType.js -> false
            KotlinPlatformType.androidJvm -> true
            KotlinPlatformType.native -> true
        }

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val target = kotlinCompilation.target
        composeCompilerArtifactProvider.checkTargetSupported(target)
        return target.project.provider {
            platformPluginOptions[target.platformType] ?: emptyList()
        }
    }

    private val platformPluginOptions = mapOf(
        KotlinPlatformType.js to options("generateDecoys" to "true")
    )

    private fun options(vararg options: Pair<String, String>): List<SubpluginOption> =
        options.map { SubpluginOption(it.first, it.second) }
}
