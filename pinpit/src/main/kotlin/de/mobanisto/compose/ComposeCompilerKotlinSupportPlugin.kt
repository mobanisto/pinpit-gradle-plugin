/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose

import de.mobanisto.compose.internal.ComposeCompilerArtifactProvider
import de.mobanisto.pinpit.PinpitExtension
import de.mobanisto.pinpit.PinpitPlugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

class ComposeCompilerKotlinSupportPlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var composeCompilerArtifactProvider: ComposeCompilerArtifactProvider

    override fun apply(target: Project) {
        super.apply(target)
        target.plugins.withType(PinpitPlugin::class.java) {
            val composeExt = target.extensions.getByType(PinpitExtension::class.java)

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
