/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import org.gradle.api.GradleException

internal fun jdkInfo(jdkVendor: String, jdkVersion: String): JdkInfo? {
    if (jdkVendor == "adoptium") {
        val match = "(\\d+)(\\.\\d+)+\\+(\\d+)".toRegex().matchEntire(jdkVersion)
            ?: throw GradleException("Invalid JDK version: $jdkVersion")
        val values = match.groupValues
        if (values.size < 3) throw GradleException("Invalid JDK version: $jdkVersion")
        val full = values[0]
        val feature = values[1]
        val more = values.subList(2, values.size - 2)
        val build = values[values.size - 1]
        return JdkInfo(full, feature.toInt(), more, build)
    }
    return null
}

// As of JEP322 the name of the first element is 'feature'. There can be an arbitrary number
// of versions after that.
data class JdkInfo(
    val full: String,
    val feature: Int,
    val more: List<String>,
    val build: String
)
