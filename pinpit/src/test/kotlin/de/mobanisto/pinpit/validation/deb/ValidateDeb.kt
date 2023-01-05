/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.validation.deb

import de.mobanisto.pinpit.test.tests.integration.NamedOutputDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import java.io.File
import java.io.InputStream
import java.lang.Integer.toOctalString
import java.nio.file.Paths

object ValidateDeb {

    /**
     * Perform some in-depth validation of the contents of the specified DEB archive.
     */
    fun validateDebContents(fis: InputStream) {
        val content = DebContentBuilder().buildContent(fis)
        validateDebPermissions(content)
    }

    /**
     * Check that files contained in DEB archive have correct ownership and permissions.
     */
    private fun validateDebPermissions(content: DebContent) {
        // scripts located in DEBIAN source folder / control.tar.xz
        val scripts = setOf("preinst", "prerm", "postinst", "postrm")
        // some shared objects that are shipped in data.tar.xz
        val executables = setOf("libapplauncher.so", "jexec", "jspawnhelper")

        assertEquals(2, content.tars.size)

        val control = content.tars["control.tar.xz"]
        val data = content.tars["data.tar.xz"]

        assertNotNull(control)
        assertNotNull(data)

        for (entry in control!!.entries) {
            val name = entry.name
            val simplePath = name.substring(2) // strip "./"
            val expectExecutable = entry.isDirectory or scripts.contains(simplePath)
            if (expectExecutable) {
                assertEquals("755", toOctalString(entry.mode))
            } else {
                assertEquals("644", toOctalString(entry.mode))
            }
            assertEquals(0, entry.group)
            assertEquals(0, entry.user)
        }

        for (entry in data!!.entries) {
            val name = entry.name
            val simplePath = name.substring(2) // strip "./"
            val path = Paths.get(simplePath)
            val expectExecutable = entry.isDirectory or
                (path.parent != null && path.parent == Paths.get("opt/test-package/bin")) or
                executables.contains(path.fileName.toString())
            if (expectExecutable) {
                assertEquals("755", toOctalString(entry.mode)) {
                    "expecting 755 on $name, but got ${toOctalString(entry.mode)}"
                }
            } else {
                assertEquals("644", toOctalString(entry.mode)) {
                    "expecting 644 on $name, but got ${toOctalString(entry.mode)}"
                }
            }
            assertEquals(0, entry.group)
            assertEquals(0, entry.user)
        }
    }

    /**
     * Finds a single *.deb file in each of the specified output directories and compares their content in-depth.
     * Takes file names, ownership, permissions and content into account. Also prints a diff for text files found to be
     * different.
     */
    fun checkDebsAreEqual(output1: NamedOutputDir, output2: NamedOutputDir) {
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
