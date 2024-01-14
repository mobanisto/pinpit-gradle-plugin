/*
 * Copyright 2023 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.unit

import de.mobanisto.pinpit.desktop.application.internal.adoptiumUrl
import de.mobanisto.pinpit.desktop.application.internal.jdkInfo
import de.mobanisto.pinpit.desktop.application.tasks.DownloadJdkTask
import org.apache.hc.client5.http.classic.methods.HttpHead
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JdkUrlTest {

    @Test
    fun `jdk16_0_2+7`() = test("16.0.2+7")

    @Test
    fun `jdk17_0_5+8`() = test("17.0.5+8")

    @Test
    fun `jdk18_0_2_1+1`() = test("18.0.2.1+1")

    @Test
    fun `jdk19_0_1+10`() = test("19.0.1+10")

    private fun test(jvmVersion: String) {
        val oss = listOf("linux", "windows", "macos")
        for (os in oss) {
            val osSource = if (os == "macos") "mac" else os
            val info = jdkInfo("adoptium", jvmVersion)
            Assertions.assertNotNull(info)
            val fileVersion = jvmVersion.replace("+", "_")
            val extension = DownloadJdkTask.osToExtension[os] ?: throw GradleException("Invalid os: $os")
            val url = adoptiumUrl(info!!, osSource, "x64", jvmVersion, fileVersion, extension)
            val head = HttpHead(url)
            val client = HttpClientBuilder.create().build()
            client.execute(head) {
                Assertions.assertEquals(200, it.code)
            }
        }
    }
}
