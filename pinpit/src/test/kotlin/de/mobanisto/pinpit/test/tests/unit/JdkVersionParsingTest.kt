/*
 * Copyright 2023 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.test.tests.unit

import de.mobanisto.pinpit.desktop.application.internal.jdkInfo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JdkVersionParsingTest {

    @Test
    fun `jdk16_0_2+7`() = test("16.0.2+7", 16)

    @Test
    fun `jdk17_0_5+8`() = test("17.0.5+8", 17)

    @Test
    fun `jdk17_0_9+9`() = test("17.0.9+9", 17)

    @Test
    fun `jdk17_0_9+9_1`() = test("17.0.9+9.1", 17)

    @Test
    fun `jdk18_0_2_1+1`() = test("18.0.2.1+1", 18)

    @Test
    fun `jdk19_0_1+10`() = test("19.0.1+10", 19)

    private fun test(jvmVersion: String, expectedFeature: Int) {
        val info = jdkInfo("adoptium", jvmVersion)
        Assertions.assertNotNull(info)
        Assertions.assertEquals(expectedFeature, info!!.feature)
        Assertions.assertEquals(jvmVersion, info.full)
    }
}
