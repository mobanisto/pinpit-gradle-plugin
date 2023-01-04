/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.tasks

import de.mobanisto.pinpit.desktop.application.internal.ExternalToolRunner
import de.mobanisto.pinpit.desktop.application.internal.ExternalToolRunnerWithOutput
import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.PinpitProperties
import de.mobanisto.pinpit.desktop.application.internal.UnixUtils
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.notNullProperty
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

abstract class AbstractPinpitTask : DefaultTask() {
    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Inject
    protected abstract val providers: ProviderFactory

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Inject
    protected abstract val fileOperations: FileOperations

    @get:LocalState
    protected val logsDir: Provider<Directory> = project.layout.buildDirectory.dir("pinpit/logs/$name")

    @get:Internal
    val verbose: Property<Boolean> = objects.notNullProperty<Boolean>().apply {
        set(
            providers.provider {
                logger.isDebugEnabled || PinpitProperties.isVerbose(providers).get()
            }
        )
    }

    @get:Internal
    internal val runExternalTool: ExternalToolRunner
        get() = ExternalToolRunner(verbose, logsDir, execOperations)

    @get:Internal
    internal val runExternalToolAndGetOutput: ExternalToolRunnerWithOutput
        get() = ExternalToolRunnerWithOutput(execOperations)

    protected fun cleanDirs(vararg dirs: Provider<out FileSystemLocation>) {
        for (dir in dirs) {
            fileOperations.delete(dir)
            fileOperations.mkdir(dir)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal fun runExternalWindowsTool(tool: File, args: List<String>) {
        if (currentOS == OS.Windows) {
            runExternalTool(tool = tool, args = args)
        } else {
            runExternalTool(
                tool = UnixUtils.wine,
                args = buildList {
                    add(tool.toString())
                    addAll(args)
                }
            )
        }
    }
}
