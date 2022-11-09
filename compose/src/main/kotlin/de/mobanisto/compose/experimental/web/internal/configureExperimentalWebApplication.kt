/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.experimental.web.internal

import de.mobanisto.compose.experimental.dsl.ExperimentalWebApplication
import de.mobanisto.compose.internal.registerTask
import de.mobanisto.compose.experimental.web.tasks.ExperimentalUnpackSkikoWasmRuntimeTask
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

internal fun KotlinJsIrTarget.configureExperimentalWebApplication(app: ExperimentalWebApplication) {
    val mainCompilation = compilations.getByName("main")
    val unpackedRuntimeDir = project.layout.buildDirectory.dir("compose/skiko-wasm/$targetName")
    val taskName = "unpackSkikoWasmRuntime${targetName.capitalize()}"
    mainCompilation.defaultSourceSet.resources.srcDir(unpackedRuntimeDir)
    val unpackRuntime = project.registerTask<ExperimentalUnpackSkikoWasmRuntimeTask>(taskName) {
        runtimeClasspath = project.configurations.getByName(mainCompilation.runtimeDependencyConfigurationName)
        outputDir.set(unpackedRuntimeDir)
    }
    mainCompilation.compileKotlinTaskProvider.configure { compileTask ->
        compileTask.dependsOn(unpackRuntime)
    }
}
