/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.application.tasks.windows.PackageMsiTask
import de.mobanisto.pinpit.desktop.application.tasks.windows.WindowsTask
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths

internal const val DOWNLOAD_WIX_TOOLSET_TASK_NAME = "downloadWix"
internal const val UNZIP_WIX_TOOLSET_TASK_NAME = "unzipWix"
internal const val WIX_PATH_ENV_VAR = "WIX_PATH"
internal const val DOWNLOAD_WIX_PROPERTY = "compose.desktop.application.downloadWix"

internal fun JvmApplicationContext.configureWix() {
    val dirHome = Paths.get(System.getProperty("user.home"))
    val dirTool = dirHome.resolve(".pinpit")
    val dirWix = dirTool.resolve("wixToolset")

    val wixPath = System.getenv()[WIX_PATH_ENV_VAR]
    if (wixPath != null) {
        val wixDir = File(wixPath)
        check(wixDir.isDirectory) { "$WIX_PATH_ENV_VAR value is not a valid directory: $wixDir" }
        project.eachWindowsPackageTask {
            wixToolsetDir.set(wixDir)
        }
        return
    }

    if (project.findProperty(DOWNLOAD_WIX_PROPERTY) == "false") return

    val root = project.rootProject
    val zipFile = dirWix.resolve("wix311.zip")
    val unzipDir = dirWix.resolve("wix311")
    val download = root.tasks.maybeCreate(DOWNLOAD_WIX_TOOLSET_TASK_NAME, Download::class.java).apply {
        onlyIf { !exists(zipFile) }
        src("https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip")
        dest(zipFile.toFile())
    }
    val unzip = root.tasks.maybeCreate(UNZIP_WIX_TOOLSET_TASK_NAME, Copy::class.java).apply {
        onlyIf { !exists(unzipDir) }
        dependsOn(download)
        from(project.zipTree(zipFile))
        destinationDir = unzipDir.toFile()
    }
    project.eachWindowsPackageTask {
        dependsOn(unzip)
        wixToolsetDir.set(unzipDir.toFile())
    }
}

private fun Project.eachWindowsPackageTask(fn: WindowsTask.() -> Unit) {
    tasks.withType(PackageMsiTask::class.java).configureEach { packageTask ->
        if (packageTask.targetFormat.isCompatibleWith(OS.Windows)) {
            packageTask.fn()
        }
    }
}
