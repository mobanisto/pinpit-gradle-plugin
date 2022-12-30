/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.desktop.application.internal.currentTarget
import de.mobanisto.pinpit.test.utils.GradlePluginTestBase
import de.mobanisto.pinpit.test.utils.TestProjects
import de.mobanisto.pinpit.test.utils.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class MultiplatformApplicationTest : GradlePluginTestBase() {

    @Test
    fun runMpp() = with(testProject(TestProjects.mpp)) {
        val targetName = currentTarget.name
        val logLine = "Kotlin MPP app is running!"
        gradle("pinpitRun").build().checks { check ->
            check.taskOutcome(":pinpitRun", TaskOutcome.SUCCESS)
            check.logContains(logLine)
        }
        gradle("pinpitRunDefaultDistributable$targetName").build().checks { check ->
            check.taskOutcome(":pinpitCreateDefaultDistributable$targetName", TaskOutcome.SUCCESS)
            check.taskOutcome(":pinpitRunDefaultDistributable$targetName", TaskOutcome.SUCCESS)
            check.logContains(logLine)
        }
    }

    /*
    @Test
    fun packageMpp() = with(testProject(TestProjects.mpp)) {
        testPackageJvmDistributions()
    }

    @Test
    fun packageUberJarForWindowsMpp() = with(testProject(TestProjects.mpp)) {
        testPackageUberJar(Target(Windows, Arch.X64))
    }

    @Test
    fun packageUberJarForLinuxMpp() = with(testProject(TestProjects.mpp)) {
        testPackageUberJar(Target(Linux, Arch.X64))
    }
    */
}
