/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.test.tests.integration

import de.mobanisto.compose.desktop.application.internal.MacUtils
import de.mobanisto.compose.desktop.application.internal.OS
import de.mobanisto.compose.desktop.application.internal.currentArch
import de.mobanisto.compose.desktop.application.internal.currentOS
import de.mobanisto.compose.desktop.application.internal.currentOsArch
import de.mobanisto.compose.desktop.application.internal.currentTarget
import de.mobanisto.compose.internal.uppercaseFirstChar
import de.mobanisto.compose.test.utils.GradlePluginTestBase
import de.mobanisto.compose.test.utils.ProcessRunResult
import de.mobanisto.compose.test.utils.TestProject
import de.mobanisto.compose.test.utils.TestProjects
import de.mobanisto.compose.test.utils.assertEqualTextFiles
import de.mobanisto.compose.test.utils.assertNotEqualTextFiles
import de.mobanisto.compose.test.utils.checkContains
import de.mobanisto.compose.test.utils.checkExists
import de.mobanisto.compose.test.utils.checks
import de.mobanisto.compose.test.utils.modify
import de.mobanisto.compose.test.utils.runProcess
import de.mobanisto.compose.validation.ValidateDeb
import org.gradle.internal.impldep.org.testng.Assert
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*
import java.util.jar.JarFile

class DesktopApplicationTest : GradlePluginTestBase() {
    @Test
    fun smokeTestRunTask() = with(testProject(TestProjects.jvm)) {
        file("build.gradle").modify {
            it + """
                afterEvaluate {
                    tasks.getByName("morun").doFirst {
                        throw new StopExecutionException("Skip run task")
                    }
                    
                    tasks.getByName("morunDistributable").doFirst {
                        throw new StopExecutionException("Skip runDistributable task")
                    }
                }
            """.trimIndent()
        }
        gradle("morun").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":morun")?.outcome)
        }
        gradle("morunDistributable").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":mocreateDistributable")!!.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":morunDistributable")?.outcome)
        }
    }

    @Test
    fun testRunMpp() = with(testProject(TestProjects.mpp)) {
        val logLine = "Kotlin MPP app is running!"
        gradle("morun").build().checks { check ->
            check.taskOutcome(":morun", TaskOutcome.SUCCESS)
            check.logContains(logLine)
        }
        gradle("morunDistributable").build().checks { check ->
            check.taskOutcome(":mocreateDistributable", TaskOutcome.SUCCESS)
            check.taskOutcome(":morunDistributable", TaskOutcome.SUCCESS)
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
                compose.desktop {
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

        val packagingTask = ":mopackageDistributionForCurrentOS"
        gradle(packagingTask).build().checks { check ->
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }

        gradle("clean", packagingTask).build().checks { check ->
            check.taskOutcome(":mocheckRuntime", TaskOutcome.FROM_CACHE)
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }
    }

    @Test
    fun packageMpp() = with(testProject(TestProjects.mpp)) {
        testPackageJvmDistributions()
    }

    private fun TestProject.testPackageJvmDistributions() {
        val result = gradle(":mopackageDistributionForCurrentOS").build()
        val ext = when (currentOS) {
            OS.Linux -> "deb"
            OS.Windows -> "msi"
            OS.MacOS -> "dmg"
        }
        val packageDir = file("build/mocompose/binaries/main/$ext")
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
        // TODO: compare contents, user ids and permissions of deb and customDeb
        // TODO: assert outcome of mopackageCustomDeb task
        assertEquals(TaskOutcome.SUCCESS, result.task(":mopackage${ext.uppercaseFirstChar()}")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":mopackageDistributionForCurrentOS")?.outcome)
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
                    compose.desktop.application {
                        javaHome = javaToolchains.launcherFor {
                            languageVersion.set(JavaLanguageVersion.of($javaVersion))
                        }.get().metadata.installationPath.asFile.absolutePath
                    }
                """.trimIndent()
            }
        }

    @Test
    fun packageCustomDeb() = with(testProject(TestProjects.jvm)) {
        testPackageCustomDeb()
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
        gradle(":mopackageUberJarForCurrentOS").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":mopackageUberJarForCurrentOS")?.outcome)

            val resultJarFile = file("build/mocompose/jars/TestPackage-${currentTarget.id}-1.0.0.jar")
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

    private fun TestProject.testPackageCustomDeb() {
        gradle(":mopackageCustomDeb").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":mopackageCustomDeb")?.outcome)

            val resultFile = file("build/mocompose/binaries/main/custom-deb/test-package_1.0.0-1_${currentOsArch}.deb")
            resultFile.checkExists()

            resultFile.inputStream().use { fis ->
                ValidateDeb().validate(fis)
            }
        }
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
                    val result = runProcess(MacUtils.codesign, args = listOf("--verify", "--verbose", appDir.absolutePath))
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
            gradle(":mosuggestRuntimeModules").build().checks { check ->
                check.taskOutcome(":mosuggestRuntimeModules", TaskOutcome.SUCCESS)
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
}
