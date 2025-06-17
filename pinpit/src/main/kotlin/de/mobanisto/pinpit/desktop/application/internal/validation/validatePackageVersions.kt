/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal.validation

import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat
import de.mobanisto.pinpit.desktop.application.internal.JvmApplicationContext
import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.desktop.application.internal.packageVersionFor
import org.gradle.api.GradleException

internal fun JvmApplicationContext.validatePackageVersions(targets: List<Target>) {
    val errors = ErrorsCollector()

    // TODO: re-enable for specific formats (RPM, DEB, PKG, DMG, etc)
    for (target in targets) {
        val versionChecker: VersionChecker = when (target.os) {
            OS.Linux -> DebVersionChecker // TODO: RpmVersionChecker
            OS.Windows -> WindowsVersionChecker
            OS.MacOS -> MacVersionChecker
        }

        val packageVersion = packageVersionFor(target.os).orNull
        if (packageVersion == null) {
            errors.addError(target, "no version was specified")
        } else {
            versionChecker.apply {
                if (!isValid(packageVersion)) {
                    errors.addError(
                        target,
                        "'$packageVersion' is not a valid version",
                        correctFormat = correctFormat
                    )
                }
            }
        }
    }

    if (errors.errors.isNotEmpty()) {
        throw GradleException(errors.errors.joinToString("\n"))
    }
}

private class ErrorsCollector {
    private val myErrors = arrayListOf<String>()

    val errors: List<String>
        get() = myErrors

    fun addError(
        target: Target,
        error: String,
        correctFormat: String? = null
    ) {
        val msg = buildString {
            appendLine("* Illegal version for '$target': $error.")
            if (correctFormat != null) {
                appendLine("  * Correct format: $correctFormat")
            }
            appendLine(
                "  * You can specify the correct version using DSL properties: " +
                        dslPropertiesFor(target).joinToString(", ")
            )
        }
        myErrors.add(msg)
    }
}

private fun dslPropertiesFor(target: Target): List<String> {
    val nativeDistributions = "nativeDistributions"
    val linux = "$nativeDistributions.linux"
    val macOS = "$nativeDistributions.macOS"
    val windows = "$nativeDistributions.windows"
    val packageVersion = "packageVersion"

    val osSettingsProperty: String = when (target.os) {
        OS.Linux -> "$linux.$packageVersion"
        OS.MacOS -> "$macOS.$packageVersion"
        OS.Windows -> "$windows.$packageVersion"
    }
    val appSpecificProperty = "$nativeDistributions.$packageVersion"
    return listOfNotNull(
        osSettingsProperty,
        appSpecificProperty,
    )
}

private fun dslPropertiesFor(targetFormat: TargetFormat): List<String> {
    val nativeDistributions = "nativeDistributions"
    val linux = "$nativeDistributions.linux"
    val macOS = "$nativeDistributions.macOS"
    val windows = "$nativeDistributions.windows"
    val packageVersion = "packageVersion"

    val formatSpecificProperty: String? = when (targetFormat) {
        is TargetFormat.DistributableApp -> null
        is TargetFormat.DistributableArchive -> null
        is TargetFormat.Deb -> "$linux.debPackageVersion"
        is TargetFormat.Rpm -> "$linux.rpmPackageVersion"
        is TargetFormat.AppImage -> "$linux.appImagePackageVersion"
        is TargetFormat.Dmg -> "$macOS.dmgPackageVersion"
        is TargetFormat.Pkg -> "$macOS.pkgPackageVersion"
        is TargetFormat.Exe -> "$windows.exePackageVersion"
        is TargetFormat.Msi -> "$windows.msiPackageVersion"
    }
    val osSettingsProperty: String = when (targetFormat.targetOS) {
        OS.Linux -> "$linux.$packageVersion"
        OS.MacOS -> "$macOS.$packageVersion"
        OS.Windows -> "$windows.$packageVersion"
    }
    val appSpecificProperty = "$nativeDistributions.$packageVersion"
    return listOfNotNull(
        formatSpecificProperty,
        osSettingsProperty,
        appSpecificProperty,
    )
}

private interface VersionChecker {
    val correctFormat: String
    fun isValid(version: String): Boolean
}

private object DebVersionChecker : VersionChecker {
    override val correctFormat = """|'[EPOCH:]UPSTREAM_VERSION[-DEBIAN_REVISION]', where:
                    |    * EPOCH is an optional non-negative integer;
                    |    * UPSTREAM_VERSION may contain only alphanumerics and the characters '.', '+', '-', '~' and must start with a digit;
                    |    * DEBIAN_REVISION is optional and may contain only alphanumerics and the characters '.', '+', '~';
                    |    * see https://www.debian.org/doc/debian-policy/ch-controlfields.html#version for details;
    """.trimMargin()

    override fun isValid(version: String): Boolean =
        version.matches(debRegex)

    private val debRegex = (
            "([0-9]+:)?" /* EPOCH */ +
                    "[0-9][0-9a-zA-Z.+\\-~]*" /* UPSTREAM_VERSION */ +
                    "(-[0-9a-zA-Z.+~]+)?" /* DEBIAN_REVISION */
            ).toRegex()
}

private object RpmVersionChecker : VersionChecker {
    override val correctFormat = "rpm package version must not contain a dash '-'"

    override fun isValid(version: String): Boolean =
        !version.contains("-")
}

private object WindowsVersionChecker : VersionChecker {
    override val correctFormat = """|'MAJOR.MINOR.BUILD', where:
        |    * MAJOR is a non-negative integer with a maximum value of 255;
        |    * MINOR is a non-negative integer with a maximum value of 255;
        |    * BUILD is a non-negative integer with a maximum value of 65535;
    """.trimMargin()

    override fun isValid(version: String): Boolean {
        val parts = version.split(".").map { it.toIntOrNull() }
        if (parts.size != 3) return false

        return parts[0].isIntInRange(0, 255) &&
                parts[1].isIntInRange(0, 255) &&
                parts[2].isIntInRange(0, 65535)
    }

    private fun Int?.isIntInRange(min: Int, max: Int) =
        this != null && this >= min && this <= max
}

private object MacVersionChecker : VersionChecker {
    override val correctFormat = """|'MAJOR[.MINOR][.PATCH]', where:
        |    * MAJOR is a non-negative integer;
        |    * MINOR is an optional non-negative integer;
        |    * PATCH is an optional non-negative integer;
    """.trimMargin()

    override fun isValid(version: String): Boolean {
        val parts = version.split(".").map { it.toIntOrNull() }

        return parts.isNotEmpty() &&
                parts.size <= 3 &&
                parts.all { it != null && it >= 0 }
    }
}
