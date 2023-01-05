/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.desktop.application.internal.Arch
import de.mobanisto.pinpit.desktop.application.internal.MacUtils
import de.mobanisto.pinpit.desktop.application.internal.OS.Linux
import de.mobanisto.pinpit.desktop.application.internal.OS.MacOS
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.currentArch
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.currentTarget
import de.mobanisto.pinpit.desktop.application.tasks.linux.JvmDebPackager
import de.mobanisto.pinpit.test.tests.integration.TestUtils.testPackageDebUbuntuFocal
import de.mobanisto.pinpit.test.tests.integration.TestUtils.testPackageJvmDistributions
import de.mobanisto.pinpit.test.tests.integration.TestUtils.testPackageMsi
import de.mobanisto.pinpit.test.tests.integration.TestUtils.testPackageUberJar
import de.mobanisto.pinpit.test.utils.GradlePluginTestBase
import de.mobanisto.pinpit.test.utils.ProcessRunResult
import de.mobanisto.pinpit.test.utils.TestProject
import de.mobanisto.pinpit.test.utils.TestProjects
import de.mobanisto.pinpit.test.utils.assertEqualTextFiles
import de.mobanisto.pinpit.test.utils.assertNotEqualTextFiles
import de.mobanisto.pinpit.test.utils.checkExists
import de.mobanisto.pinpit.test.utils.checks
import de.mobanisto.pinpit.test.utils.modify
import de.mobanisto.pinpit.test.utils.runProcess
import de.mobanisto.pinpit.validation.deb.DebContent
import de.mobanisto.pinpit.validation.deb.DebContentBuilder
import de.mobanisto.pinpit.validation.deb.NativeDebPackager
import de.mobanisto.pinpit.validation.deb.ValidateDeb.checkDebsAreEqual
import org.gradle.internal.impldep.org.testng.Assert
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.util.Calendar
import kotlin.io.path.createDirectories

class DesktopApplicationTest : GradlePluginTestBase() {
    @Test
    fun targetNameIsAsExpected() {
        val targetName = currentTarget.name
        assertTrue(targetName == "LinuxX64" || targetName == "WindowsX64")
    }

    @Test
    fun smokeTestRunTask() = with(testProject(TestProjects.jvm)) {
        val targetName = currentTarget.name
        file("build.gradle").modify {
            it + """
                afterEvaluate {
                    tasks.getByName("pinpitRun").doFirst {
                        throw new StopExecutionException("Skip run task")
                    }
                    
                    tasks.getByName("pinpitRunDefaultDistributable$targetName").doFirst {
                        throw new StopExecutionException("Skip runDistributable task")
                    }
                }
            """.trimIndent()
        }
        gradle("pinpitRun").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitRun")?.outcome)
        }
        gradle("pinpitRunDefaultDistributable$targetName").build().let { result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitCreateDefaultDistributable$targetName")!!.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":pinpitRunDefaultDistributable$targetName")?.outcome)
        }
    }

    @Test
    fun androidxCompiler() = with(testProject(TestProjects.androidxCompiler, defaultAndroidxCompilerEnvironment)) {
        val targetName = currentTarget.name
        gradle(":pinpitRunDefaultDistributable$targetName").build().checks { check ->
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

        val targetName = currentTarget.name

        checkImageBeforeBuild()
        gradle(":pinpitRunReleaseDistributable$targetName").build().checks { check ->
            check.taskOutcome(":pinpitProguardReleaseJars$targetName", TaskOutcome.SUCCESS)
            checkImageAfterBuild()
            assertEqualTextFiles(file("main-methods.actual.txt"), file("main-methods.expected.txt"))
        }

        file("build.gradle").modify { "$it\n$enableObfuscation" }
        actualMainImage.delete()
        checkImageBeforeBuild()
        gradle(":pinpitRunReleaseDistributable$targetName").build().checks { check ->
            check.taskOutcome(":pinpitProguardReleaseJars$targetName", TaskOutcome.SUCCESS)
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

        val packagingTask = ":pinpitPackageDefault"
        gradle(packagingTask).build().checks { check ->
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }

        gradle("clean", packagingTask).build().checks { check ->
            check.taskOutcome(":pinpitCheckRuntimeLinuxX64", TaskOutcome.FROM_CACHE)
            check.taskOutcome(":pinpitCheckRuntimeWindowsX64", TaskOutcome.FROM_CACHE)
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }
    }

    @Test
    fun jdk16() = with(customJdkProject(16, "16.0.2+7")) {
        testPackageJvmDistributions()
    }

    @Test
    fun jdk17() = with(customJdkProject(17, "17.0.5+8")) {
        testPackageJvmDistributions()
    }

    @Test
    fun jdk18() = with(customJdkProject(18, "18.0.2+9")) {
        // Latest released version is actually jdk-18.0.2.1+1, but we currently do not support another dot and number
        // after the patch version.
        testPackageJvmDistributions()
    }

    @Test
    fun jdk19() = with(customJdkProject(19, "19.0.1+10")) {
        testPackageJvmDistributions()
    }

    private fun customJdkProject(javaVersion: Int, jvmVersion: String): TestProject =
        testProject(TestProjects.jvm, jvmVersion).apply {
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
    fun packageDebAndCompareContentWithNativePackaging() {
        Assumptions.assumeTrue(currentOS == Linux)
        Assumptions.assumeTrue(currentArch == Arch.X64)
        with(testProject(TestProjects.jvm)) {
            testPackageDebsAndCompareContent()
        }
    }

    private fun TestProject.testPackageDebsAndCompareContent() {
        val packagingTask = ":pinpitPackageDefaultDebUbuntuFocalX64"
        gradle(packagingTask).build().checks { check ->
            check.taskOutcome(packagingTask, TaskOutcome.SUCCESS)
        }

        val dirBuild = file("build").toPath()
        val dirBinaries = dirBuild.resolve("pinpit/binaries/main-default/linux/x64/")

        val outputDirNativePackaging = packageDebNative()
        val outputDirJvmPackaging = packageDebJvm()

        val outputDirPinpitPackaging = dirBinaries.resolve("deb")

        val pinpit = NamedOutputDir("pinpit", outputDirPinpitPackaging)
        val native = NamedOutputDir("native", outputDirNativePackaging)
        val jvm = NamedOutputDir("jvm", outputDirJvmPackaging)

        checkDebsAreEqual(pinpit, native)
        checkDebsAreEqual(pinpit, jvm)
    }

    private fun TestProject.packageDebJvm(): Path {
        val dirNativePackaging = testWorkDir.resolve("jvm-deb")

        val outputDir = dirNativePackaging.resolve("output")
        val workingDir = dirNativePackaging.resolve("working")
        outputDir.createDirectories()
        workingDir.createDirectories()

        val deb = outputDir.resolve("test-package-ubuntu-20.04-1.0.0.deb")

        val dirBuild = file("build").toPath()
        val dirBinaries = dirBuild.resolve("pinpit/binaries/main-default/linux/x64/")

        val packaging = file("src/main/packaging").toPath()
        val appImage = dirBinaries.resolve("appimage/TestPackage")
        val packager = JvmDebPackager(
            appImage,
            deb,
            workingDir,
            "TestPackage",
            "test-package",
            "1.0.0",
            "utils",
            "Test Vendor",
            "example@example.com",
            "Test description",
            listOf("libc6", "libexpat1", "libgcc-s1", "libpcre3", "libuuid1", "xdg-utils", "zlib1g", "libnotify4"),
            packaging.resolve("deb/copyright"),
            packaging.resolve("deb/launcher.desktop"),
            packaging.resolve("deb/preinst"),
            packaging.resolve("deb/postinst"),
            packaging.resolve("deb/prerm"),
            packaging.resolve("deb/postrm"),
        )
        packager.createPackage()
        return outputDir
    }

    private fun TestProject.packageDebNative(): Path {
        val dirNativePackaging = testWorkDir.resolve("native-deb")

        val outputDir = dirNativePackaging.resolve("output")
        val workingDir = dirNativePackaging.resolve("working")
        outputDir.createDirectories()
        workingDir.createDirectories()

        val deb = outputDir.resolve("test-package-ubuntu-20.04-1.0.0.deb")

        val dirBuild = file("build").toPath()
        val dirBinaries = dirBuild.resolve("pinpit/binaries/main-default/linux/x64/")

        val packaging = file("src/main/packaging").toPath()
        val appImage = dirBinaries.resolve("appimage/TestPackage")
        val packager = NativeDebPackager(
            appImage,
            deb,
            workingDir,
            "TestPackage",
            "test-package",
            "1.0.0",
            "utils",
            "Test Vendor",
            "example@example.com",
            "Test description",
            listOf("libc6", "libexpat1", "libgcc-s1", "libpcre3", "libuuid1", "xdg-utils", "zlib1g", "libnotify4"),
            packaging.resolve("deb/copyright"),
            packaging.resolve("deb/launcher.desktop"),
            packaging.resolve("deb/preinst"),
            packaging.resolve("deb/postinst"),
            packaging.resolve("deb/prerm"),
            packaging.resolve("deb/postrm"),
        )
        packager.createPackage()
        return outputDir
    }

    @Test
    fun packageUberJarForWindowsJvm() = with(testProject(TestProjects.jvm)) {
        testPackageUberJar(Target(Windows, Arch.X64))
    }

    @Test
    fun packageUberJarForLinuxJvm() = with(testProject(TestProjects.jvm)) {
        testPackageUberJar(Target(Linux, Arch.X64))
    }

    @Test
    fun moduleClash() = with(testProject(TestProjects.moduleClashCli)) {
        gradle(":app:runDistributable").build().checks { check ->
            check.taskOutcome(":app:createDistributable", TaskOutcome.SUCCESS)
            check.taskOutcome(":app:runDistributable", TaskOutcome.SUCCESS)
            check.logContains("Called lib1#util()")
            check.logContains("Called lib2#util()")
        }
    }

    @Test
    fun javaLogger() = with(testProject(TestProjects.javaLogger)) {
        gradle(":runDistributable").build().checks { check ->
            check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)
            check.logContains("Compose Gradle plugin test log warning!")
        }
    }

    @Test
    fun macOptions() {
        fun String.normalized(): String =
            trim().replace(
                "Copyright (C) ${Calendar.getInstance().get(Calendar.YEAR)}",
                "Copyright (C) CURRENT_YEAR"
            )

        Assumptions.assumeTrue(currentOS == MacOS)

        with(testProject(TestProjects.macOptions)) {
            gradle(":runDistributable").build().checks { check ->
                check.taskOutcome(":runDistributable", TaskOutcome.SUCCESS)
                check.logContains("Hello, from Mac OS!")
                val appDir = file("build/compose/binaries/main/app/TestPackage.app/Contents/")
                val actualInfoPlist = appDir.resolve("Info.plist").checkExists()
                val expectedInfoPlist = file("Expected-Info.Plist")
                val actualInfoPlistNormalized = actualInfoPlist.readText().normalized()
                val expectedInfoPlistNormalized = expectedInfoPlist.readText().normalized()
                Assert.assertEquals(actualInfoPlistNormalized, expectedInfoPlistNormalized)
            }
        }
    }

    @Test
    fun macSign() {
        Assumptions.assumeTrue(currentOS == MacOS)

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
                    val appDir = file("build/compose/binaries/main/app/TestPackage.app/")
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
    fun optionsWithSpaces() {
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
    fun defaultArgs() {
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
    fun defaultArgsOverride() {
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
    fun suggestModules() {
        val targetName = currentTarget.name
        with(testProject(TestProjects.jvm)) {
            gradle(":pinpitSuggestRuntimeModules$targetName").build().checks { check ->
                check.taskOutcome(":pinpitSuggestRuntimeModules$targetName", TaskOutcome.SUCCESS)
                check.logContains("Suggested runtime modules to include:")
                check.logContains("modules(\"java.instrument\", \"jdk.unsupported\")")
            }
        }
    }

    @Test
    fun unpackSkiko() {
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
                    Linux -> listOf("libskiko-linux-${currentArch.id}.so")
                    Windows -> listOf("skiko-windows-${currentArch.id}.dll", "icudtl.dat")
                    MacOS -> listOf("libskiko-macos-${currentArch.id}.dylib")
                }
                for (fileName in filesToFind) {
                    skikoDir.resolve(fileName).checkExists()
                }
            }
        }
    }

    @Test
    fun resources() = with(testProject(TestProjects.resources)) {
        gradle(":pinpitRun").build().checks { check ->
            check.taskOutcome(":pinpitRun", TaskOutcome.SUCCESS)
        }

        val targetName = currentTarget.name

        gradle(":pinpitRunDefaultDistributable$targetName").build().checks { check ->
            check.taskOutcome(":pinpitRunDefaultDistributable$targetName", TaskOutcome.SUCCESS)
        }
    }

    @Test
    fun debOneAdditionalDependency(): Unit = with(testProject(TestProjects.jvm)) {
        val extraPackage = "libnotify4"
        val addPackage = addDebPackage(listOf(extraPackage))
        file("build.gradle").modify { "$it\n$addPackage" }

        gradle(":pinpitPackageDefaultDebCustomDistroX64").build().checks { check ->
            check.taskOutcome(":pinpitPackageDefaultDebCustomDistroX64", TaskOutcome.SUCCESS)
            checkDebControlFile { content ->
                assertTrue(content.contains(extraPackage))
            }
        }
    }

    @Test
    fun debTwoAdditionalDependencies(): Unit = with(testProject(TestProjects.jvm)) {
        val extraPackages = listOf("libnotify4", "bluez")
        val addPackage = addDebPackage(extraPackages)
        file("build.gradle").modify { "$it\n$addPackage" }

        gradle(":pinpitPackageDefaultDebCustomDistroX64").build().checks { check ->
            check.taskOutcome(":pinpitPackageDefaultDebCustomDistroX64", TaskOutcome.SUCCESS)
            checkDebControlFile { content ->
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

    @Test
    fun debContentHasCorrectPermissions(): Unit = with(testProject(TestProjects.jvm)) {
        val executables = setOf(
            "./opt/test-package/bin/TestPackage",
            "./opt/test-package/lib/libapplauncher.so",
            "./opt/test-package/lib/runtime/lib/jexec",
            "./opt/test-package/lib/runtime/lib/jspawnhelper",
        )

        gradle(":pinpitPackageDefaultDebUbuntuFocalX64").build().checks { check ->
            check.taskOutcome(":pinpitPackageDefaultDebUbuntuFocalX64", TaskOutcome.SUCCESS)
            checkDebData { content ->
                val data = content.tars["data.tar.xz"]
                assertNotNull(data)
                data?.entries?.forEach { entry ->
                    assertEquals(0, entry.group) {
                        "${entry.name} has group set to 0"
                    }
                    assertEquals(0, entry.user) {
                        "${entry.name} has user set to 0"
                    }
                    val permission = if (entry.isDirectory || executables.contains(entry.name)) "755" else "644"
                    assertEquals(permission.toInt(radix = 8), entry.mode) {
                        "${entry.name} has mode set to $permission but got ${entry.mode.toString(radix = 8)}"
                    }
                }
            }
        }
    }

    private fun TestProject.checkDebControlFile(check: (content: String) -> Unit) {
        val packageDir = file("build/pinpit/binaries/main-default/linux/x64/deb")
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

    private fun TestProject.checkDebData(check: (content: DebContent) -> Unit) {
        val packageDir = file("build/pinpit/binaries/main-default/linux/x64/deb")
        val packageDirFiles = packageDir.listFiles() ?: arrayOf()
        check(packageDirFiles.size == 1) {
            "Expected single package in $packageDir, got [${packageDirFiles.joinToString(", ") { it.name }}]"
        }
        val packageFile = packageDirFiles.single()
        val content = DebContentBuilder().buildContent(packageFile)
        check(content)
    }
}
