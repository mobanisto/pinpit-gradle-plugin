/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.test.tests.integration.TestUtils.testPackageJvmDistributions
import de.mobanisto.pinpit.test.utils.GradlePluginTestBase
import de.mobanisto.pinpit.test.utils.TestProject
import de.mobanisto.pinpit.test.utils.TestProjects
import org.junit.jupiter.api.Test

class JdkVersionsTest : GradlePluginTestBase() {

    @Test
    fun jdk16() = with(customJdkProject(16, "16.0.2+7")) {
        testPackageJvmDistributions()
    }

    @Test
    fun jdk17() = with(customJdkProject(17, "17.0.5+8")) {
        testPackageJvmDistributions()
    }

    @Test
    fun jdk18() = with(customJdkProject(18, "18.0.2.1+1")) {
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
}
