/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.test.utils.GradlePluginTestBase
import de.mobanisto.pinpit.test.utils.TestProjects
import de.mobanisto.pinpit.test.utils.TestProperties
import de.mobanisto.pinpit.test.utils.checks
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class GradlePluginTest : GradlePluginTestBase() {
    @Test
    fun jsMppIsNotBroken() =
        with(
            testProject(
                TestProjects.jsMpp,
                testEnvironment = defaultTestEnvironment.copy(
                    kotlinVersion = TestProperties.composeJsCompilerCompatibleKotlinVersion
                )
            )
        ) {
            gradle(":compileKotlinJs").build().checks { check ->
                check.taskOutcome(":compileKotlinJs", TaskOutcome.SUCCESS)
            }
        }
}
