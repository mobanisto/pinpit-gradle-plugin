/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.windows

import de.mobanisto.pinpit.desktop.application.internal.JvmApplicationContext
import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.tasks.DistributableAppTask
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths

internal const val DOWNLOAD_PE_REBRANDER_TASK_NAME = "pinpitDownloadPeRebrander"
internal const val UNZIP_PE_REBRANDER_TASK_NAME = "pinpitUnzipPeRebrander"
internal const val PE_REBRANDER_PATH_ENV_VAR = "PE_REBRANDER_PATH"
internal const val DOWNLOAD_PE_REBRANDER_PROPERTY = "compose.desktop.application.downloadPeRebrander"

internal fun JvmApplicationContext.configurePeRebrander() {
    val dirHome = Paths.get(System.getProperty("user.home"))
    val dirTool = dirHome.resolve(".pinpit")
    val dirPeRebrander = dirTool.resolve("peRebrander")

    val peRebranderPath = System.getenv()[PE_REBRANDER_PATH_ENV_VAR]
    if (peRebranderPath != null) {
        val peRebranderDir = File(peRebranderPath)
        check(peRebranderDir.isDirectory) { "$PE_REBRANDER_PATH_ENV_VAR value is not a valid directory: $peRebranderDir" }
        project.eachWindowsPackageTask {
            this.peRebranderDir.set(peRebranderDir)
        }
        return
    }

    if (project.findProperty(DOWNLOAD_PE_REBRANDER_PROPERTY) == "false") return

    var version = "1.0.0"

    val root = project.rootProject
    val zipFile = dirPeRebrander.resolve("PE-Rebrander-$version.zip")
    val unzipDir = dirPeRebrander.resolve("PE-Rebrander-$version")
    val download = root.tasks.maybeCreate(DOWNLOAD_PE_REBRANDER_TASK_NAME, Download::class.java).apply {
        onlyIf { !exists(zipFile) }
        src("https://github.com/mobanisto/pe-rebrander/releases/download/release-$version/PE-Rebrander-$version.zip")
        dest(zipFile.toFile())
    }
    val unzip = root.tasks.maybeCreate(UNZIP_PE_REBRANDER_TASK_NAME, Copy::class.java).apply {
        onlyIf { !exists(unzipDir) }
        dependsOn(download)
        from(project.zipTree(zipFile))
        destinationDir = unzipDir.toFile()
    }
    project.eachWindowsPackageTask {
        dependsOn(unzip)
        peRebranderDir.set(unzipDir.toFile())
    }
}

private fun Project.eachWindowsPackageTask(fn: WindowsTask.() -> Unit) {
    tasks.withType(DistributableAppTask::class.java).configureEach { packageTask ->
        if (packageTask.target.os == OS.Windows) {
            packageTask.fn()
        }
    }
    tasks.withType(PackageMsiTask::class.java).configureEach { packageTask ->
        if (packageTask.targetFormat.isCompatibleWith(OS.Windows)) {
            packageTask.fn()
        }
    }
}
