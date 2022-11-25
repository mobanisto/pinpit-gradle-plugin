/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.application.dsl.JvmApplicationDistributions
import org.gradle.api.provider.Provider

internal fun JvmApplicationContext.packageVersionFor(os: OS): Provider<String?> =
    project.provider {
        app.nativeDistributions.packageVersionFor(os)
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

private fun JvmApplicationDistributions.packageVersionFor(os: OS): String? {
    val osSpecificVersion: String? = when (os) {
        OS.Linux -> linux.packageVersion
        OS.MacOS -> macOS.packageVersion
        OS.Windows -> windows.packageVersion
    }
    return osSpecificVersion
        ?: packageVersion
}
