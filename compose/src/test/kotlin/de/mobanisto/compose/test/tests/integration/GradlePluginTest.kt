/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.test.tests.integration

import org.gradle.testkit.runner.TaskOutcome
import de.mobanisto.compose.test.utils.*
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

    private fun testConfigureDesktopPreivewImpl(port: Int) {
        check(port > 0) { "Invalid port: $port" }
        with(testProject(TestProjects.jvmPreview)) {
            val portProperty = "-Pcompose.desktop.preview.ide.port=$port"
            val previewTargetProperty = "-Pcompose.desktop.preview.target=PreviewKt.ExamplePreview"
            val jvmTask = ":jvm:configureDesktopPreview"
            gradle(jvmTask, portProperty, previewTargetProperty)
                .build()
                .checks { check ->
                    check.taskOutcome(jvmTask, TaskOutcome.SUCCESS)
                }

            val mppTask = ":mpp:configureDesktopPreviewDesktop"
            gradle(mppTask, portProperty, previewTargetProperty)
                .build()
                .checks { check ->
                    check.taskOutcome(mppTask, TaskOutcome.SUCCESS)
                }
        }
    }
}
