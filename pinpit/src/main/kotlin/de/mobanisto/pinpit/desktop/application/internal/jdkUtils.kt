/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import org.gradle.api.GradleException
import java.net.URLEncoder

internal fun jdkInfo(jdkVendor: String, jdkVersion: String): JdkInfo? {
    if (jdkVendor == "adoptium") {
        val match = "(\\d+)((\\.\\d+)+)?\\+(\\d+)((\\.\\d+)+)?".toRegex().matchEntire(jdkVersion)
            ?: throw GradleException("Invalid JDK version: $jdkVersion")
        val values = match.groupValues
        val full = values[0]
        val feature = values[1]
        val more = values[2].split(".").filterNot { it.isBlank() }
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

fun adoptiumUrl(
    info: JdkInfo,
    osSource: String,
    arch: String,
    jvmVersion: String,
    fileVersion: String,
    extension: String
): String {
    val urlJvmVersion = when {
        (osSource == "windows" && jvmVersion == "17.0.9+9") -> "17.0.9+9.1"
        else -> jvmVersion
    }
    val urlVersion = URLEncoder.encode(urlJvmVersion, Charsets.UTF_8)
    val url = "https://github.com/adoptium/temurin${info.feature}-binaries/releases/download/" +
        "jdk-$urlVersion/OpenJDK${info.feature}U-jdk_${arch}_${osSource}_hotspot_$fileVersion.$extension"
    return url
}
