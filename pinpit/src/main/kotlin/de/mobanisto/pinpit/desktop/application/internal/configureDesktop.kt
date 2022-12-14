/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.DesktopExtension
import org.gradle.api.Project

internal fun configureDesktop(project: Project, desktopExtension: DesktopExtension) {
    if (desktopExtension._isJvmApplicationInitialized) {
        val appInternal = desktopExtension.application as JvmApplicationInternal
        val defaultBuildType = appInternal.data.buildTypes.default
        val appData = JvmApplicationContext(project, appInternal, defaultBuildType)
        appData.configureJvmApplication()
    }
}
