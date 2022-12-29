/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks

import de.mobanisto.pinpit.desktop.application.internal.PinpitProperties
import de.mobanisto.pinpit.desktop.application.internal.notNullProperty
import de.mobanisto.pinpit.desktop.tasks.AbstractPinpitTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.InputChanges
import java.io.File

abstract class AbstractCustomTask() : AbstractPinpitTask() {
    @get:LocalState
    protected val workingDir: Provider<Directory> = project.layout.buildDirectory.dir("pinpit/tmp/$name")

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:Input
    @get:Optional
    val freeArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Internal
    val javaHome: Property<String> = objects.notNullProperty<String>().apply {
        set(providers.systemProperty("java.home"))
    }

    protected open fun prepareWorkingDir(inputChanges: InputChanges) {
        cleanDirs(workingDir)
    }

    protected open fun makeArgs(tmpDir: File): MutableList<String> = arrayListOf<String>().apply {
        freeArgs.orNull?.forEach { add(it) }
    }

    @TaskAction
    fun run(inputChanges: InputChanges) {
        initState()

        fileOperations.delete(destinationDir)
        prepareWorkingDir(inputChanges)

        try {
            runTask().also { checkResult() }
        } finally {
            if (!PinpitProperties.preserveWorkingDir(providers).get()) {
                fileOperations.delete(workingDir)
            }
        }
        saveStateAfterFinish()
    }

    protected open fun initState() {}
    protected open fun runTask() {}
    protected open fun checkResult() {}
    protected open fun saveStateAfterFinish() {}
}
