package de.mobanisto.pinpit.desktop.application.tasks.windows

import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional

interface WindowsTask : Task {

    @get:InputDirectory
    @get:Optional
    val wixToolsetDir: DirectoryProperty

}
