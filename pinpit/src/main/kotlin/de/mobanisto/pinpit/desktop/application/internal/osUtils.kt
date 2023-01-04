/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.application.internal.files.checkExistingFile
import de.mobanisto.pinpit.desktop.application.tasks.MIN_JAVA_RUNTIME_VERSION
import de.mobanisto.pinpit.internal.uppercaseFirstChar
import org.gradle.api.provider.Provider
import java.io.File
import java.io.Serializable

enum class OS(val id: String) {
    Linux("linux"),
    Windows("windows"),
    MacOS("macos")
}

enum class Arch(val id: String) {
    X64("x64"),
    Arm64("arm64")
}

internal fun arch(arch: String?): Arch {
    for (candidate in Arch.values()) {
        if (candidate.id == arch) {
            return candidate
        }
    }
    error("Invalid architecture '$arch'")
}

data class Target(val os: OS, val arch: Arch) : Serializable {
    val id: String
        get() = "${os.id}-${arch.id}"
    val configuration: String
        get() = "${os.id}${arch.id.uppercaseFirstChar()}"
    val name: String
        get() = "${os.id.uppercaseFirstChar()}${arch.id.uppercaseFirstChar()}"
}

internal val currentTarget by lazy {
    Target(currentOS, currentArch)
}

internal val currentOsArch by lazy {
    System.getProperty("os.arch")
}

internal val currentArch by lazy {
    when (val osArch = System.getProperty("os.arch")) {
        "x86_64", "amd64" -> Arch.X64
        "aarch64" -> Arch.Arm64
        else -> error("Unsupported OS arch: $osArch")
    }
}

internal val currentOS: OS by lazy {
    val os = System.getProperty("os.name")
    when {
        os.equals("Mac OS X", ignoreCase = true) -> OS.MacOS
        os.startsWith("Win", ignoreCase = true) -> OS.Windows
        os.startsWith("Linux", ignoreCase = true) -> OS.Linux
        else -> error("Unknown OS name: $os")
    }
}

internal fun OS.isUnix(): Boolean {
    return this == OS.Linux || this == OS.MacOS
}

internal fun executableName(nameWithoutExtension: String): String =
    if (currentOS == OS.Windows) "$nameWithoutExtension.exe" else nameWithoutExtension

internal fun javaExecutable(javaHome: String): String =
    File(javaHome).resolve("bin/${executableName("java")}").absolutePath

internal object DebianUtils {
    val dpkg: File by lazy {
        File("/usr/bin/dpkg").checkExistingFile()
    }
    val ldd: File by lazy {
        File("/usr/bin/ldd").checkExistingFile()
    }
}

internal object MacUtils {
    val codesign: File by lazy {
        File("/usr/bin/codesign").checkExistingFile()
    }

    val security: File by lazy {
        File("/usr/bin/security").checkExistingFile()
    }

    val xcrun: File by lazy {
        File("/usr/bin/xcrun").checkExistingFile()
    }

    val xcodeBuild: File by lazy {
        File("/usr/bin/xcodebuild").checkExistingFile()
    }

    val make: File by lazy {
        File("/usr/bin/make").checkExistingFile()
    }

    val open: File by lazy {
        File("/usr/bin/open").checkExistingFile()
    }
}

internal object UnixUtils {
    val git: File by lazy {
        File("/usr/bin/git").checkExistingFile()
    }
    val wine: File by lazy {
        File("/opt/wine-stable/bin/wine").checkExistingFile()
    }
    val winepath: File by lazy {
        File("/opt/wine-stable/bin/winepath").checkExistingFile()
    }
}

internal fun jvmToolFile(toolName: String, javaHome: Provider<String>): File =
    jvmToolFile(toolName, File(javaHome.get()))

internal fun jvmToolFile(toolName: String, javaHome: File): File {
    val jtool = javaHome.resolve("bin/${executableName(toolName)}")
    check(jtool.isFile) {
        "Invalid JDK: $jtool is not a file! \n" +
            "Ensure JAVA_HOME or buildSettings.javaHome is set to JDK $MIN_JAVA_RUNTIME_VERSION or newer"
    }
    return jtool
}
