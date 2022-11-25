package de.mobanisto.pinpit.desktop.application.internal

import org.gradle.api.GradleException

internal fun jdkInfo(jdkVendor: String, jdkVersion: String): JdkInfo? {
    if (jdkVendor == "adoptium") {
        val match = "(\\d+).(\\d+).(\\d+)\\+(\\d+)".toRegex().matchEntire(jdkVersion)
            ?: throw GradleException("Invalid version")
        val (full, major, minor, patch, build) = match.groupValues
        return JdkInfo(full, major, minor, patch, build)
    }
    return null
}

data class JdkInfo(val full: String, val major: String, val minor: String, val patch: String, val build: String)
