/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.utils

import de.mobanisto.pinpit.desktop.application.internal.PinpitProperties
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.Properties

data class TestEnvironment(
    val projectDir: File,
    val kotlinVersion: String = TestKotlinVersions.Default,
    val composeGradlePluginVersion: String = TestProperties.composeGradlePluginVersion,
    val pinpitGradlePluginVersion: String = TestProperties.pinpitGradlePluginVersion,
    val pinpitJvmVendor: String = TestProperties.pinpitJvmVendor,
    val pinpitJvmVersion: String = TestProperties.pinpitJvmVersion,
    val composeCompilerArtifact: String? = null,
) {
    private val placeholders = linkedMapOf(
        "PINPIT_GRADLE_PLUGIN_VERSION_PLACEHOLDER" to pinpitGradlePluginVersion,
        "COMPOSE_GRADLE_PLUGIN_VERSION_PLACEHOLDER" to composeGradlePluginVersion,
        "KOTLIN_VERSION_PLACEHOLDER" to kotlinVersion,
        "COMPOSE_COMPILER_ARTIFACT_PLACEHOLDER" to composeCompilerArtifact,
        "PINPIT_JVM_VENDOR" to pinpitJvmVendor,
        "PINPIT_JVM_VERSION" to pinpitJvmVersion,
    )

    fun replacePlaceholdersInFile(file: File) {
        var content = file.readText()
        for ((placeholder, value) in placeholders.entries) {
            if (value != null) {
                content = content.replace(placeholder, value)
            }
        }
        file.writeText(content)
    }
}

data class Subproject(val dir: String, val name: String)

class TestProject(
    private val name: String,
    private val testEnvironment: TestEnvironment,
    val pinpitSubproject: Subproject? = null,
) {
    private val testProjectsRootDir = File("src/test/test-projects")
    private val additionalArgs = listOf(
        "--stacktrace",
        "--init-script", testProjectsRootDir.resolve("init.gradle").absolutePath,
        "-P${PinpitProperties.VERBOSE}=true"
    )

    init {
        val originalTestRoot = testProjectsRootDir.resolve(name).also {
            check(it.exists()) { "Test project is not found: ${it.absolutePath}" }
        }
        for (orig in originalTestRoot.walk()) {
            if (!orig.isFile) continue

            val target = testEnvironment.projectDir.resolve(orig.relativeTo(originalTestRoot))
            target.parentFile.mkdirs()
            orig.copyTo(target)

            if (orig.name.endsWith(".gradle") || orig.name.endsWith(".gradle.kts") ||
                orig.name == "gradle.properties"
            ) {
                testEnvironment.replacePlaceholdersInFile(target)
            }
        }
    }

    fun gradle(vararg args: String): GradleRunner =
        GradleRunner.create().apply {
            withGradleVersion(TestProperties.gradleVersionForTests)
            withProjectDir(testEnvironment.projectDir)
            withArguments(args.toList() + additionalArgs)
            forwardOutput()
        }

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("Do not commit!")
    fun gradleDebug(vararg args: String): GradleRunner =
        gradle(*args).withDebug(true)

    fun file(path: String): File =
        testEnvironment.projectDir.resolve(path)

    fun modifyText(path: String, fn: (String) -> String) {
        val file = file(path)
        val oldContent = file.readText()
        val newContent = fn(oldContent)
        file.writeText(newContent)
    }

    fun appendText(path: String, fn: () -> String) {
        val file = file(path)
        val oldContent = file.readText()
        val newContent = oldContent + "\n" + fn()
        file.writeText(newContent)
    }

    fun modifyGradleProperties(fn: Properties.() -> Unit) {
        val propertiesFile = file("gradle.properties")
        val properties = Properties()
        if (propertiesFile.exists()) {
            propertiesFile.bufferedReader().use { reader ->
                properties.load(reader)
            }
        }
        fn(properties)
        propertiesFile.delete()

        if (properties.isNotEmpty()) {
            propertiesFile.bufferedWriter().use { writer ->
                properties.store(writer, null)
            }
        }
    }
}
