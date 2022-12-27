/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.desktop.application.internal.MacUtils
import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.currentArch
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.currentOsArch
import de.mobanisto.pinpit.desktop.application.internal.currentTarget
import de.mobanisto.pinpit.internal.uppercaseFirstChar
import de.mobanisto.pinpit.test.utils.GradlePluginTestBase
import de.mobanisto.pinpit.test.utils.ProcessRunResult
import de.mobanisto.pinpit.test.utils.TestProject
import de.mobanisto.pinpit.test.utils.TestProjects
import de.mobanisto.pinpit.test.utils.assertEqualTextFiles
import de.mobanisto.pinpit.test.utils.assertNotEqualTextFiles
import de.mobanisto.pinpit.test.utils.checkContains
import de.mobanisto.pinpit.test.utils.checkExists
import de.mobanisto.pinpit.test.utils.checks
import de.mobanisto.pinpit.test.utils.modify
import de.mobanisto.pinpit.test.utils.runProcess
import de.mobanisto.pinpit.validation.deb.DebContentBuilder
import de.mobanisto.pinpit.validation.deb.DebContentUtils
import de.mobanisto.pinpit.validation.deb.ValidateDeb
import org.gradle.internal.impldep.org.testng.Assert
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*
import java.util.jar.JarFile

class DesktopApplicationTest : GradlePluginTestBase() {
    @Test
    fun smokeTestRunTask() = with(testProject(TestProjects.jvm)) {
        val targetName = currentTarget.name
        file("build.gradle").modify {
            it + """
                afterEvaluate {
                    tasks.getByName("pinpitRun").doFirst {
                        throw new StopExecutionException("Skip run task")
                    }
                    
                    tasks.getByName("pinpitRunDistributable$targetName").doFirst {
                        throw new StopExecutionException("Skip runDistributable task")
                    }
                }
            """.trimIndent()
        }
        gradle("pinpitRun").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitRun")?.outcome)
        }
        gradle("pinpitRunDistributable$targetName").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitCreateDistributable$targetName")!!.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitRunDistributable$targetName")?.outcome)
        }
    }

    @Test
    fun testRunMpp() = with(testProject(TestProjects.mpp)) {
        val logLine = "Kotlin MPP app is running!"
        gradle("pinpitRun").build().checks { check ->
            check.taskOutcome(":pinpitRun", TaskOutcome.SUCCESS)
            check.logContains(logLine)
        }
        gradle("pinpitRunDistributable").build().checks { check ->
            check.taskOutcome(":pinpitCreateDistributable", TaskOutcome.SUCCESS)
            check.taskOutcome(":pinpitRunDistributable", TaskOutcome.SUCCESS)
            check.logContains(logLine)
        }
    }

    @Test
    fun testAndroidxCompiler() = with(testProject(TestProjects.androidxCompiler, defaultAndroidxCompilerEnvironment)) {
        gradle(":runDistributable").build().checks { check ->
            val actualMainImage = file("main-image.actual.png")
            val expectedMainImage = file("main-image.expected.png")
            assert(actualMainImage.readBytes().contentEquals(expectedMainImage.readBytes())) {
                "The actual image '$actualMainImage' does not match the expected image '$expectedMainImage'"
            }
        }
    }

    @Test
    fun kotlinDsl(): Unit = with(testProject(TestProjects.jvmKotlinDsl)) {
        gradle(":packageDistributionForCurrentOS", "--dry-run").build()
        gradle(":packageReleaseDistributionForCurrentOS", "--dry-run").build()
    }

    @Test
    fun proguard(): Unit = with(testProject(TestProjects.proguard)) {
        val enableObfuscation = """
                pinpit.desktop {
                    application {
                        buildTypes.release.proguard {
                            obfuscate.set(true)
                        }
                    }
                }
            """.trimIndent()

        val actualMainImage = file("main-image.actual.png")
        val expectedMainImage = file("main-image.expected.png")

        fun checkImageBeforeBuild() {
            assertFalse(actualMainImage.exists(), "'$actualMainImage' exists")
        }

        fun checkImageAfterBuild() {
            assert(actualMainImage.readBytes().contentEquals(expectedMainImage.readBytes())) {
                "The actual image '$actualMainImage' does not match the expected image '$expectedMainImage'"
            }
        }

        checkImageBeforeBuild()
        gradle(":runReleaseDistributable").build().checks { check ->
            check.taskOutcome(":proguardReleaseJars", TaskOutcome.SUCCESS)
            checkImageAfterBuild()
            assertEqualTextFiles(file("main-methods.actual.txt"), file("main-methods.expected.txt"))
        }

        file("build.gradle").modify { "$it\n$enableObfuscation" }
        actualMainImage.delete()
        checkImageBeforeBuild()
        gradle(":runReleaseDistributable").build().checks { check ->
            check.taskOutcome(":proguardReleaseJars", TaskOutcome.SUCCESS)
            checkImageAfterBuild()
            assertNotEqualTextFiles(file("main-methods.actual.txt"), file("main-methods.expected.txt"))
        }
    }

    @Test
    fun packageJvm() = with(testProject(TestProjects.jvm)) {
        testPackageJvmDistributions()
    }

    @Test
    fun gradleBuildCache() = with(testProject(TestProjects.jvm)) {
        modifyGradleProperties {
            setProperty("org.gradle.caching", "true")
        }
        modifyText("settings.gradle") {
            it + "\n" + """
                buildCache {
                    local {
                        directory = new File(rootDir, 'build-cache')
                    }
                }
            """.trimIndent()
        }

        val packagingTask = ":pinpitPackageDistributionForCurrentOS"
        gradle(packagingTask).build().checks { check ->
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }

        gradle("clean", packagingTask).build().checks { check ->
            check.taskOutcome(":pinpitCheckRuntime", TaskOutcome.FROM_CACHE)
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }
    }

    @Test
    fun packageMpp() = with(testProject(TestProjects.mpp)) {
        testPackageJvmDistributions()
    }

    private fun TestProject.testPackageJvmDistributions() {
        val result = gradle(":pinpitPackageDistributionForCurrentOS").build()
        val ext = when (currentOS) {
            OS.Linux -> "deb"
            OS.Windows -> "msi"
            OS.MacOS -> "dmg"
        }
        val packageDir = file("build/pinpit/binaries/main/$ext")
        val packageDirFiles = packageDir.listFiles() ?: arrayOf()
        check(packageDirFiles.size == 1) {
            "Expected single package in $packageDir, got [${packageDirFiles.joinToString(", ") { it.name }}]"
        }
        val packageFile = packageDirFiles.single()

        if (currentOS == OS.Linux) {
            val isTestPackage = packageFile.name.contains("test-package", ignoreCase = true) ||
                    packageFile.name.contains("testpackage", ignoreCase = true)
            val isDeb = packageFile.name.endsWith(".$ext")
            check(isTestPackage && isDeb) {
                "Expected contain testpackage*.deb or test-package*.deb package in $packageDir, got '${packageFile.name}'"
            }
        } else {
            Assert.assertEquals(packageFile.name, "TestPackage-1.0.0.$ext", "Unexpected package name")
        }
        // TODO: assert outcome of pinpitPackageCustomDeb task
        assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitPackage${ext.uppercaseFirstChar()}")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitPackageDistributionForCurrentOS")?.outcome)
    }

    @Test
    fun testJdk15() = with(customJdkProject(15)) {
        testPackageJvmDistributions()
    }

    @Test
    fun testJdk17() = with(customJdkProject(17)) {
        testPackageJvmDistributions()
    }

    @Test
    fun testJdk18() = with(customJdkProject(18)) {
        testPackageJvmDistributions()
    }

    @Test
    fun testJdk19() = with(customJdkProject(19)) {
        testPackageJvmDistributions()
    }

    private fun customJdkProject(javaVersion: Int): TestProject =
        testProject(TestProjects.jvm).apply {
            appendText("build.gradle") {
                """
                    pinpit.desktop.application {
                        javaHome = javaToolchains.launcherFor {
                            languageVersion.set(JavaLanguageVersion.of($javaVersion))
                        }.get().metadata.installationPath.asFile.absolutePath
                    }
                """.trimIndent()
            }
        }

    @Test
    fun tasks() = with(testProject(TestProjects.jvm)) {
        gradle(":tasks").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
        }
    }

    @Test
    fun packageDebUbuntuFocal() = with(testProject(TestProjects.jvm)) {
        testPackageDebUbuntuFocal()
    }

    @Test
    fun packageMsi() = with(testProject(TestProjects.jvm)) {
        testPackageMsi()
    }

    @Test
    fun packageCustomDeb() = with(testProject(TestProjects.jvm)) {
        testPackageCustomDeb()
    }

    @Test
    fun packageDebsAndCompareContent() = with(testProject(TestProjects.jvm)) {
        testPackageDebsAndCompareContent()
    }

    @Test
    fun packageUberJarForCurrentOSJvm() = with(testProject(TestProjects.jvm)) {
        testPackageUberJarForCurrentOS()
    }

    @Test
    fun packageUberJarForCurrentOSMpp() = with(testProject(TestProjects.mpp)) {
        testPackageUberJarForCurrentOS()
    }

    private fun TestProject.testPackageUberJarForCurrentOS() {
        gradle(":pinpitPackageUberJarForCurrentOS").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitPackageUberJarForCurrentOS")?.outcome)

            val resultJarFile = file("build/pinpit/jars/TestPackage-${currentTarget.id}-1.0.0.jar")
            resultJarFile.checkExists()

            JarFile(resultJarFile).use { jar ->
                val mainClass = jar.manifest.mainAttributes.getValue("Main-Class")
                assertEquals("MainKt", mainClass, "Unexpected main class")

                jar.entries().toList().mapTo(HashSet()) { it.name }.apply {
                    checkContains("MainKt.class", "org/jetbrains/skiko/SkiaLayer.class")
                }
            }
        }
    }

    private fun TestProject.testPackageDebUbuntuFocal() {
        gradle(":pinpitDebUbuntuFocalX64").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitDebUbuntuFocalX64")?.outcome)

            val resultFile = file("build/pinpit/binaries/main/linux/x64/deb/test-package-ubuntu-20.04-x64-1.0.0.deb")
            resultFile.checkExists()

            resultFile.inputStream().use { fis ->
                ValidateDeb.validate(fis)
            }
        }
    }

    private fun TestProject.testPackageMsi() {
        gradle(":pinpitMsiX64").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitMsiX64")?.outcome)

            val resultFile = file("build/pinpit/binaries/main/windows/x64/msi/TestPackage-x64-1.0.0.msi")
            resultFile.checkExists()
        }
    }

    private fun TestProject.testPackageCustomDeb() {
        gradle(":pinpitPackageCustomDeb").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitPackageCustomDeb")?.outcome)

            val resultFile = file("build/pinpit/binaries/main/linux/x64/deb/test-package_1.0.0-1_$currentOsArch.deb")
            resultFile.checkExists()

            resultFile.inputStream().use { fis ->
                ValidateDeb.validate(fis)
            }
        }
    }

    private fun TestProject.testPackageDebsAndCompareContent() {
        val result = gradle(":pinpitPackageDeb", ":pinpitPackageCustomDeb").build()

        val packageDirStock = file("build/pinpit/binaries/main/deb")
        val packageDirCustom = file("build/pinpit/binaries/main/custom-deb")
        val packageDirs = listOf(packageDirStock, packageDirCustom)

        val debs = mutableListOf<File>()

        for (packageDir in packageDirs) {
            val packageDirFiles = packageDir.listFiles() ?: arrayOf()
            check(packageDirFiles.size == 1) {
                "Expected single package in $packageDir, got [${packageDirFiles.joinToString(", ") { it.name }}]"
            }
            val packageFile = packageDirFiles.single()
            debs.add(packageFile)
            val isTestPackage = packageFile.name.contains("test-package", ignoreCase = true) ||
                    packageFile.name.contains("testpackage", ignoreCase = true)
            val isDeb = packageFile.name.endsWith(".deb")
            check(isTestPackage && isDeb) {
                "Expected contain testpackage*.deb or test-package*.deb package in $packageDir, got '${packageFile.name}'"
            }
            println("got package file at ${packageFile}")
        }
        assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitPackageDeb")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitPackageCustomDeb")?.outcome)

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
        for (entry in comparison.entries) {
            val tarComparison = entry.value
            allClear = allClear && tarComparison.onlyIn1.isEmpty() && tarComparison.onlyIn2.isEmpty()
                    && tarComparison.different.isEmpty()
        }
        if (!allClear) {
            println("Found differences among deb files produced")
            for (entry in comparison.entries) {
                println("  Differences in ${entry.key}:")
                val tarComparison = entry.value
                tarComparison.onlyIn1.forEach { println("    only in stock deb:  $it") }
                tarComparison.onlyIn2.forEach { println("    only in custom deb: $it") }
                tarComparison.different.forEach { println("    both but different (stock):  ${it.first}") }
                tarComparison.different.forEach { println("    both but different (custom): ${it.second}") }
            }
            println("Showing files with differences:")
            DebContentUtils.printDiff(debs[0], debs[1], comparison)
        }
        check(allClear) { "Differences found in stock and custom deb" }
    }

    @Test
    fun testModuleClash() = with(testProject(TestProjects.moduleClashCli)) {
        gradle(":app:runDistributable").build().checks { check ->
            check.taskOutcome(":app:createDistributable", TaskOutcome.SUCCESS)
            check.taskOutcome(":app:runDistributable", TaskOutcome.SUCCESS)
            check.logContains("Called lib1#util()")
            check.logContains("Called lib2#util()")
        }
    }

    @Test
    fun testJavaLogger() = with(testProject(TestProjects.javaLogger)) {
        gradle(":runDistributable").build().checks { check ->
            check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)
            check.logContains("Compose Gradle plugin test log warning!")
        }
    }

    @Test
    fun testMacOptions() {
        fun String.normalized(): String =
            trim().replace(
                "Copyright (C) ${Calendar.getInstance().get(Calendar.YEAR)}",
                "Copyright (C) CURRENT_YEAR"
            )

        Assumptions.assumeTrue(currentOS == OS.MacOS)

        with(testProject(TestProjects.macOptions)) {
            gradle(":runDistributable").build().checks { check ->
                check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)
                check.logContains("Hello, from Mac OS!")
                val appDir = testWorkDir.resolve("build/compose/binaries/main/app/TestPackage.app/Contents/")
                val actualInfoPlist = appDir.resolve("Info.plist").checkExists()
                val expectedInfoPlist = testWorkDir.resolve("Expected-Info.Plist")
                val actualInfoPlistNormalized = actualInfoPlist.readText().normalized()
                val expectedInfoPlistNormalized = expectedInfoPlist.readText().normalized()
                Assert.assertEquals(actualInfoPlistNormalized, expectedInfoPlistNormalized)
            }
        }
    }

    @Test
    fun testMacSign() {
        Assumptions.assumeTrue(currentOS == OS.MacOS)

        fun security(vararg args: Any): ProcessRunResult {
            val args = args.map {
                if (it is File) it.absolutePath else it.toString()
            }
            return runProcess(MacUtils.security, args)
        }

        fun withNewDefaultKeychain(newKeychain: File, fn: () -> Unit) {
            val originalKeychain =
                security("default-keychain")
                    .out
                    .trim()
                    .trim('"')

            try {
                security("default-keychain", "-s", newKeychain)
                fn()
            } finally {
                security("default-keychain", "-s", originalKeychain)
            }
        }

        with(testProject(TestProjects.macSign)) {
            val keychain = file("compose.test.keychain")
            val password = "compose.test"

            withNewDefaultKeychain(keychain) {
                security("default-keychain", "-s", keychain)
                security("unlock-keychain", "-p", password, keychain)

                gradle(":createDistributable").build().checks { check ->
                    check.taskOutcome(":createDistributable", TaskOutcome.SUCCESS)
                    val appDir = testWorkDir.resolve("build/compose/binaries/main/app/TestPackage.app/")
                    val result =
                        runProcess(MacUtils.codesign, args = listOf("--verify", "--verbose", appDir.absolutePath))
                    val actualOutput = result.err.trim()
                    val expectedOutput = """
                        |${appDir.absolutePath}: valid on disk
                        |${appDir.absolutePath}: satisfies its Designated Requirement
                    """.trimMargin().trim()
                    Assert.assertEquals(expectedOutput, actualOutput)
                }

                gradle(":runDistributable").build().checks { check ->
                    check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)
                    check.logContains("Signed app successfully started!")
                }
            }
        }
    }

    @Test
    fun testOptionsWithSpaces() {
        with(testProject(TestProjects.optionsWithSpaces)) {
            fun testRunTask(runTask: String) {
                gradle(runTask).build().checks { check ->
                    check.taskOutcome(runTask, TaskOutcome.SUCCESS)
                    check.logContains("Running test options with spaces!")
                    check.logContains("Arg #1=Value 1!")
                    check.logContains("Arg #2=Value 2!")
                    check.logContains("JVM system property arg=Value 3!")
                }
            }

            testRunTask(":runDistributable")
            testRunTask(":run")

            gradle(":packageDistributionForCurrentOS").build().checks { check ->
                check.taskOutcome(":packageDistributionForCurrentOS", TaskOutcome.SUCCESS)
            }
        }
    }

    @Test
    fun testDefaultArgs() {
        with(testProject(TestProjects.defaultArgs)) {
            fun testRunTask(runTask: String) {
                gradle(runTask).build().checks { check ->
                    check.taskOutcome(runTask, TaskOutcome.SUCCESS)
                    check.logContains("compose.application.configure.swing.globals=true")
                }
            }

            testRunTask(":runDistributable")
            testRunTask(":run")

            gradle(":packageDistributionForCurrentOS").build().checks { check ->
                check.taskOutcome(":packageDistributionForCurrentOS", TaskOutcome.SUCCESS)
            }
        }
    }

    @Test
    fun testDefaultArgsOverride() {
        with(testProject(TestProjects.defaultArgsOverride)) {
            fun testRunTask(runTask: String) {
                gradle(runTask).build().checks { check ->
                    check.taskOutcome(runTask, TaskOutcome.SUCCESS)
                    check.logContains("compose.application.configure.swing.globals=false")
                }
            }

            testRunTask(":runDistributable")
            testRunTask(":run")

            gradle(":packageDistributionForCurrentOS").build().checks { check ->
                check.taskOutcome(":packageDistributionForCurrentOS", TaskOutcome.SUCCESS)
            }
        }
    }

    @Test
    fun testSuggestModules() {
        with(testProject(TestProjects.jvm)) {
            gradle(":pinpitSuggestRuntimeModules").build().checks { check ->
                check.taskOutcome(":pinpitSuggestRuntimeModules", TaskOutcome.SUCCESS)
                check.logContains("Suggested runtime modules to include:")
                check.logContains("modules(\"java.instrument\", \"jdk.unsupported\")")
            }
        }
    }

    @Test
    fun testUnpackSkiko() {
        with(testProject(TestProjects.unpackSkiko)) {
            gradle(":runDistributable").build().checks { check ->
                check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)

                val libraryPathPattern = "Read skiko library path: '(.*)'".toRegex()
                val m = libraryPathPattern.find(check.log)
                val skikoDir = m?.groupValues?.get(1)?.let(::File)
                if (skikoDir == null || !skikoDir.exists()) {
                    error("Invalid skiko path: $skikoDir")
                }
                val filesToFind = when (currentOS) {
                    OS.Linux -> listOf("libskiko-linux-${currentArch.id}.so")
                    OS.Windows -> listOf("skiko-windows-${currentArch.id}.dll", "icudtl.dat")
                    OS.MacOS -> listOf("libskiko-macos-${currentArch.id}.dylib")
                }
                for (fileName in filesToFind) {
                    skikoDir.resolve(fileName).checkExists()
                }
            }
        }
    }

    @Test
    fun resources() = with(testProject(TestProjects.resources)) {
        gradle(":run").build().checks { check ->
            check.taskOutcome(":run", TaskOutcome.SUCCESS)
        }

        gradle(":runDistributable").build().checks { check ->
            check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)
        }
    }

    @Test
    fun debOneAdditionalDependency(): Unit = with(testProject(TestProjects.jvm)) {
        val extraPackage = "libnotify4"
        val addPackage = addDebPackage(listOf(extraPackage))
        file("build.gradle").modify { "$it\n$addPackage" }

        gradle(":pinpitDebCustomDistroX64").build().checks { check ->
            check.taskOutcome(":pinpitDebCustomDistroX64", TaskOutcome.SUCCESS)
            checkDebContent { content ->
                assertTrue(content.contains(extraPackage))
            }
        }
    }

    @Test
    fun debTwoAdditionalDependencies(): Unit = with(testProject(TestProjects.jvm)) {
        val extraPackages = listOf("libnotify4", "bluez")
        val addPackage = addDebPackage(extraPackages)
        file("build.gradle").modify { "$it\n$addPackage" }

        gradle(":pinpitDebCustomDistroX64").build().checks { check ->
            check.taskOutcome(":pinpitDebCustomDistroX64", TaskOutcome.SUCCESS)
            checkDebContent { content ->
                for (extraPackage in extraPackages) {
                    assertTrue(content.contains(extraPackage))
                }
            }
        }
    }

    private fun addDebPackage(packages: List<String>): String {
        return """
                pinpit.desktop {
                    application {
                        nativeDistributions.linux {
                            deb("CustomDistroX64") {
                                qualifier = "custom-distro"
                                arch = "x64"
                                depends("libc6", "libexpat1", "libgcc-s1", "libpcre3", "libuuid1", "xdg-utils",
                                        "zlib1g", ${packages.joinToString(", ") { "\"$it\"" }})
                            }
                        }
                    }
                }
            """.trimIndent()
    }

    private fun TestProject.checkDebContent(check: (content: String) -> Unit) {
        val packageDir = file("build/pinpit/binaries/main/linux/x64/deb")
        val packageDirFiles = packageDir.listFiles() ?: arrayOf()
        check(packageDirFiles.size == 1) {
            "Expected single package in $packageDir, got [${packageDirFiles.joinToString(", ") { it.name }}]"
        }
        val packageFile = packageDirFiles.single()
        val contentBytes = DebContentBuilder().getControl(packageFile)
        checkNotNull(contentBytes)
        val content = String(contentBytes)
        check(content)
    }

}
