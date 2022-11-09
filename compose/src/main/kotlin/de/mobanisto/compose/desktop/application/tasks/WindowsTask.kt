package de.mobanisto.compose.desktop.application.tasks

import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty

interface WindowsTask : Task {

    val wixToolsetDir: DirectoryProperty

}