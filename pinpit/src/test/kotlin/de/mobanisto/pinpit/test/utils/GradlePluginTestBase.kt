/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.utils

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

abstract class GradlePluginTestBase {
    @TempDir
    lateinit var testWorkDir: Path

    private val projectDir: File
        get() = testWorkDir.resolve("project").toFile()

    val defaultTestEnvironment: TestEnvironment
        get() = TestEnvironment(projectDir = projectDir)

    val defaultAndroidxCompilerEnvironment: TestEnvironment
        get() = defaultTestEnvironment.copy(
            kotlinVersion = TestKotlinVersions.AndroidxCompatible,
            composeGradlePluginVersion = "1.2.1",
            composeCompilerArtifact = "androidx.compose.compiler:compiler:${TestProperties.androidxCompilerVersion}"
        )

    fun testProject(
        name: String,
        testEnvironment: TestEnvironment = defaultTestEnvironment,
        pinpitSubproject: Subproject? = null,
    ): TestProject =
        TestProject(name, testEnvironment = testEnvironment, pinpitSubproject = pinpitSubproject)

    fun testProject(
        name: String,
        jvmVersion: String,
        pinpitSubproject: Subproject? = null,
    ): TestProject =
        TestProject(
            name,
            testEnvironment = TestEnvironment(
                projectDir = projectDir,
                pinpitJvmVersion = jvmVersion
            ),
            pinpitSubproject = pinpitSubproject
        )
}
