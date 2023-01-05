/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.validation.deb

import de.mobanisto.pinpit.test.tests.integration.NamedOutputDir
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.junit.jupiter.api.Assertions
import java.io.File
import java.io.InputStream
import java.lang.Integer.toOctalString
import java.nio.file.Paths

object ValidateDeb {
    fun validate(fis: InputStream) {
        // scripts located in DEBIAN source folder / control.tar.xz
        val scripts = setOf("preinst", "prerm", "postinst", "postrm")
        // some shared objects that are shipped in data.tar.xz
        val executables = setOf("libapplauncher.so", "jexec", "jspawnhelper")
        val ais = ArArchiveInputStream(fis)
        while (true) {
            val entry1: ArArchiveEntry = ais.nextArEntry ?: break
            val name1 = entry1.name
            println("$name1: ${entry1.groupId}, ${entry1.userId}, ${toOctalString(entry1.mode)}")
            if (name1.endsWith("tar.xz")) {
                val xz = XZCompressorInputStream(ais)
                val tar = TarArchiveInputStream(xz)
                while (true) {
                    val entry2 = tar.nextTarEntry ?: break
                    val name2 = entry2.name
                    val simplePath = name2.substring(2) // strip "./"
                    val path = Paths.get(simplePath)
                    val expectExecutable = entry2.isDirectory or scripts.contains(simplePath) or
                        (path.parent != null && path.parent.fileName.toString() == "bin") or
                        executables.contains(path.fileName.toString())
                    println("  $name2: ${entry2.longGroupId}, ${entry2.longUserId}, ${toOctalString(entry2.mode)}")
                    if (expectExecutable) {
                        Assertions.assertEquals("755", toOctalString(entry2.mode))
                    } else {
                        Assertions.assertEquals("644", toOctalString(entry2.mode))
                    }
                }
            }
        }
    }

    fun checkDebExpectations(output1: NamedOutputDir, output2: NamedOutputDir) {
        val debs = mutableListOf<File>()

        for (namedOutput in listOf(output1, output2)) {
            val packageDirFiles = namedOutput.dir.toFile().listFiles() ?: arrayOf()
            check(packageDirFiles.size == 1) {
                "Expected single package in $namedOutput, got [${packageDirFiles.joinToString(", ") { it.name }}]"
            }
            val packageFile = packageDirFiles.single()
            debs.add(packageFile)
            val isTestPackage = packageFile.name.contains("test-package", ignoreCase = true) ||
                packageFile.name.contains("testpackage", ignoreCase = true)
            val isDeb = packageFile.name.endsWith(".deb")
            check(isTestPackage && isDeb) {
                "Expected contain testpackage*.deb or test-package*.deb package in $namedOutput, got '${packageFile.name}'"
            }
            println("got package file at $packageFile")
        }

        check(debs.size == 2)
        val debContent = debs.map { file ->
            file.inputStream().use { input ->
                DebContentBuilder().buildContent(input)
            }
        }
        val deb1 = debContent[0]
        val deb2 = debContent[1]
        val comparison = DebContentUtils.compare(deb1, deb2)
        var allClear = true
        for (entry in comparison.tarComparisonResult.entries) {
            val tarComparison = entry.value
            allClear = allClear && tarComparison.onlyIn1.isEmpty() && tarComparison.onlyIn2.isEmpty() &&
                tarComparison.different.isEmpty()
        }
        val arComparison = comparison.arComparisonResult
        allClear = allClear && arComparison.onlyIn1.isEmpty() &&
            arComparison.onlyIn2.isEmpty() && arComparison.different.isEmpty()

        val name1 = output1.name
        val name2 = output2.name

        if (!allClear) {
            println("Found differences among deb files produced")
            for (entry in comparison.tarComparisonResult.entries) {
                println("  Differences in ${entry.key}:")
                val tarComparison = entry.value
                tarComparison.onlyIn1.forEach { println("    only in $name1 deb:  $it") }
                tarComparison.onlyIn2.forEach { println("    only in $name2 deb: $it") }
                tarComparison.different.forEach { println("    both but different ($name1):  ${it.first}") }
                tarComparison.different.forEach { println("    both but different ($name2): ${it.second}") }
            }

            println("  Differences in top level archive:")
            arComparison.onlyIn1.forEach { println("    only in $name1 deb:  $it") }
            arComparison.onlyIn2.forEach { println("    only in $name2 deb: $it") }
            arComparison.different.forEach { println("    both but different ($name1):  ${it.first}") }
            arComparison.different.forEach { println("    both but different ($name2): ${it.second}") }

            println("Showing files with differences:")
            DebContentUtils.printDiff(debs[0], debs[1], comparison.tarComparisonResult)
        }
        check(allClear) { "Differences found in $name1 and $name2 deb" }
    }
}
