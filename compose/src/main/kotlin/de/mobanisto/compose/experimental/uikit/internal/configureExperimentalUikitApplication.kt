/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.experimental.uikit.internal

import org.gradle.api.Project
import de.mobanisto.compose.desktop.application.internal.OS
import de.mobanisto.compose.desktop.application.internal.currentOS
import de.mobanisto.compose.experimental.dsl.ExperimentalUiKitApplication
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal fun Project.configureExperimentalUikitApplication(
    mppExt: KotlinMultiplatformExtension,
    application: ExperimentalUiKitApplication
) {
    if (currentOS != OS.MacOS) return

    configureIosDeployTasks(
        mppExt = mppExt,
        application = application,
    )
}
