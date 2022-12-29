/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.dsl

import de.mobanisto.pinpit.desktop.application.internal.PinpitProperties
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import javax.inject.Inject

abstract class MacOSNotarizationSettings {
    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Inject
    protected abstract val providers: ProviderFactory

    @get:Input
    @get:Optional
    val appleID: Property<String?> = objects.nullableProperty<String>().apply {
        set(PinpitProperties.macNotarizationAppleID(providers))
    }

    @get:Input
    @get:Optional
    val password: Property<String?> = objects.nullableProperty<String>().apply {
        set(PinpitProperties.macNotarizationPassword(providers))
    }

    @get:Input
    @get:Optional
    val ascProvider: Property<String?> = objects.nullableProperty<String>().apply {
        set(PinpitProperties.macNotarizationAscProvider(providers))
    }
}
