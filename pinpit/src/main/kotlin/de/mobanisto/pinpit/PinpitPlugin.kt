/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:Suppress("unused")

package de.mobanisto.pinpit

import de.mobanisto.pinpit.desktop.DesktopExtension
import de.mobanisto.pinpit.desktop.application.internal.ComposeProperties
import de.mobanisto.pinpit.desktop.application.internal.configureDesktop
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.ComponentModuleMetadataHandler
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class PinpitPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val pinpitExtension = project.extensions.create("pinpit", PinpitExtension::class.java)
        val desktopExtension = pinpitExtension.extensions.create("desktop", DesktopExtension::class.java)

        project.plugins.apply(ComposeCompilerKotlinSupportPlugin::class.java)

        project.afterEvaluate {
            configureDesktop(project, desktopExtension)

            fun ComponentModuleMetadataHandler.replaceAndroidx(original: String, replacement: String) {
                module(original) {
                    it.replacedBy(replacement, "org.jetbrains.compose isn't compatible with androidx.compose, because it is the same library published with different maven coordinates")
                }
            }

            val overrideDefaultJvmTarget = ComposeProperties.overrideKotlinJvmTarget(project.providers).get()
            project.tasks.withType(KotlinCompile::class.java) {
                it.kotlinOptions.apply {
                    if (overrideDefaultJvmTarget) {
                        if (jvmTarget.isNullOrBlank() || jvmTarget.toDouble() < 1.8) {
                             jvmTarget = "1.8"
                         }
                    }
                }
            }
        }
    }
}
