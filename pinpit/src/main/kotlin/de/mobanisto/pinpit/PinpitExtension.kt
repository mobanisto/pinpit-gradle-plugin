/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit

import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class PinpitExtension @Inject constructor(
    objects: ObjectFactory
) : ExtensionAware {
    val kotlinCompilerPlugin: Property<String?> = objects.nullableProperty()
}

