/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.validation.deb

import de.mobanisto.pinpit.desktop.application.tasks.linux.AbstractDebPackager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute
import kotlin.io.path.createDirectories

/**
 * A class for creating a DEB package using native tools fakeroot and dpkg-deb. This is used in the tests to make sure
 * our platform-independent DEB packaging matches native packaging.
 */
class NativeDebPackager constructor(
    private val appImage: Path,
    private val destinationDeb: Path,
    workingDir: Path,
    packageName: String,
    linuxPackageName: String,
     packageVersion: String,
    appCategory: String,
    packageVendor: String,
    debMaintainer: String,
    packageDescription: String,
    depends: List<String>,
    debCopyright: Path?,
    debLauncher: Path?,
    debPreInst: Path?,
    debPostInst: Path?,
    debPreRm: Path?,
    debPostRm: Path?,
) : AbstractDebPackager(
    workingDir,
    packageName,
    linuxPackageName,
    packageVersion,
    appCategory,
    packageVendor,
    debMaintainer,
    packageDescription,
    depends,
    debCopyright,
    debLauncher,
    debPreInst,
    debPostInst,
    debPreRm,
    debPostRm
) {

    private val logger: Logger = LoggerFactory.getLogger(NativeDebPackager::class.java)

    fun createPackage() {
        logger.info("destination: $destinationDeb")
        destinationDeb.parent.createDirectories(asFileAttribute(posixExecutable))

        logger.info("app image: $appImage")

        logger.info("building debian file tree at: $debFileTree")
        debFileTree.createDirectories(asFileAttribute(posixExecutable))

        buildDebFileTree(appImage, debFileTree)
        buildDebianDir(appImage, debFileTree)

        runExternalTool(
            tool = "/usr/bin/fakeroot",
            args = listOf("/usr/bin/dpkg-deb", "-b", debFileTree.toString(), destinationDeb.toString())
        )
    }

    private fun runExternalTool(tool: String, args: List<String>) {
        val cmdline = mutableListOf<String>().apply {
            add(tool)
            addAll(args)
        }
        val process = ProcessBuilder(cmdline).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw IllegalStateException("Command $args returned with value $exitCode")
    }
}
