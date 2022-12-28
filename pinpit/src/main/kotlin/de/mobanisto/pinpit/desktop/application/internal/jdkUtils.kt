/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import org.gradle.api.GradleException

internal fun jdkInfo(jdkVendor: String, jdkVersion: String): JdkInfo? {
    if (jdkVendor == "adoptium") {
        val match = "(\\d+).(\\d+).(\\d+)\\+(\\d+)".toRegex().matchEntire(jdkVersion)
            ?: throw GradleException("Invalid JDK version: $jdkVersion")
        val (full, major, minor, patch, build) = match.groupValues
        return JdkInfo(full, major.toInt(), minor, patch, build)
    }
    return null
}

data class JdkInfo(val full: String, val major: Int, val minor: String, val patch: String, val build: String)
