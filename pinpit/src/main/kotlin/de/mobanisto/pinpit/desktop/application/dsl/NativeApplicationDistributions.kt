/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.dsl

import org.gradle.api.Action
import java.util.EnumSet

abstract class NativeApplicationDistributions : AbstractDistributions() {
    private val supportedFormats = setOf(TargetFormat.Dmg())

    val macOS: NativeApplicationMacOSPlatformSettings = objects.newInstance(NativeApplicationMacOSPlatformSettings::class.java)
    open fun macOS(fn: Action<NativeApplicationMacOSPlatformSettings>) {
        fn.execute(macOS)
    }
}
