/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.internal.Arch
import de.mobanisto.pinpit.desktop.application.internal.DebianUtils
import de.mobanisto.pinpit.desktop.application.internal.currentArch
import de.mobanisto.pinpit.desktop.tasks.AbstractComposeDesktopTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.inject.Inject


abstract class SuggestDebDependenciesTask @Inject constructor() : AbstractComposeDesktopTask() {

    companion object {
        private val PACKAGE_NAME_REGEX: Pattern = Pattern.compile("^(^\\S+):")
        private val LIB_IN_LDD_OUTPUT_REGEX = Pattern.compile("^\\s*\\S+\\s*=>\\s*(\\S+)\\s+\\(0[xX]\\p{XDigit}+\\)")
    }

    @get:InputDirectory
    val appImage: DirectoryProperty = objects.directoryProperty()

    @TaskAction
    fun run() {
        val debArch = if (currentArch == Arch.X64) "amd64" else
            throw GradleException("Undefined debian architecture for target architecture $currentArch")

        // Determine package dependencies as in jdk.jpackage.internal.LibProvidersLookup and
        // jdk.jpackage.internal.LinuxDebBundler
        val packages = findPackageDependencies(debArch)
        println("arch packages: ${packages.archPackages}")
        println("other packages: ${packages.otherPackages}")
        println("if you ship an icon: xdg-utils")
    }

    data class FindPackageResults(val archPackages: Set<String>, val otherPackages: Set<String>)

    private fun findPackageDependencies(debArch: String): FindPackageResults {
        val set = mutableSetOf<Path>()
        for (file in appImage.asFileTree.filter { canDependOnLibs(it) }) {
            val resultLdd = runExternalToolAndGetOutput(
                tool = DebianUtils.ldd,
                args = listOf(file.toString())
            )
            resultLdd.stdout.lines().forEach { line ->
                val matcher = LIB_IN_LDD_OUTPUT_REGEX.matcher(line)
                if (matcher.find()) {
                    set.add(Paths.get(matcher.group(1)))
                }
            }
        }
        logger.lifecycle("lib files: $set")

        val archPackages = mutableSetOf<String>()
        val otherPackages = mutableSetOf<String>()

        for (path in set) {
            val resultDpkg = runExternalToolAndGetOutput(
                tool = DebianUtils.dpkg,
                args = listOf("-S", path.toString())
            )
            resultDpkg.stdout.lines().forEach { line ->
                val matcher: Matcher = PACKAGE_NAME_REGEX.matcher(line)
                if (matcher.find()) {
                    var name: String = matcher.group(1)
                    if (name.endsWith(":$debArch")) {
                        name = name.substring(0, name.length - (debArch.length + 1))
                        archPackages.add(name)
                    } else {
                        otherPackages.add(name)
                    }
                }
            }
        }

        return FindPackageResults(archPackages, otherPackages)
    }

    private fun canDependOnLibs(file: File): Boolean {
        return file.canExecute() || file.toString().endsWith(".so")
    }
}
