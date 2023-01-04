/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks

import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.RuntimeCompressionLevel
import de.mobanisto.pinpit.desktop.application.internal.cliArg
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.notNullProperty
import de.mobanisto.pinpit.desktop.application.internal.nullableProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import java.io.File
import java.nio.file.Path

// todo: public DSL
// todo: deduplicate if multiple runtimes are created
abstract class AbstractJLinkTask : AbstractJvmToolOperationTask("jlink") {
    @Internal
    val jdk: Property<Path> = objects.notNullProperty()

    @get:Input
    val modules: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    val includeAllModules: Property<Boolean> = objects.notNullProperty()

    @get:InputFile
    val javaRuntimePropertiesFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    internal val stripDebug: Property<Boolean> = objects.notNullProperty(true)

    @get:Input
    internal val noHeaderFiles: Property<Boolean> = objects.notNullProperty(true)

    @get:Input
    internal val noManPages: Property<Boolean> = objects.notNullProperty(true)

    @get:Input
    internal val stripNativeCommands: Property<Boolean> = objects.notNullProperty(true)

    @get:Input
    @get:Optional
    internal val compressionLevel: Property<RuntimeCompressionLevel?> = objects.nullableProperty()

    override fun makeArgs(tmpDir: File): MutableList<String> = super.makeArgs(tmpDir).apply {
        val modulesToInclude =
            if (includeAllModules.get()) {
                JvmRuntimeProperties.readFromFile(javaRuntimePropertiesFile.ioFile).availableModules
            } else modules.get()
        modulesToInclude.forEach { m ->
            cliArg("--add-modules", m)
        }

        val jmods = jdk.get().resolve("jmods")
        cliArg("--module-path", jmods)

        cliArg("--strip-debug", stripDebug)
        cliArg("--no-header-files", noHeaderFiles)
        cliArg("--no-man-pages", noManPages)
        cliArg("--strip-native-commands", stripNativeCommands)
        cliArg("--compress", compressionLevel.orNull?.id)

        cliArg("--output", destinationDir)
    }
}
