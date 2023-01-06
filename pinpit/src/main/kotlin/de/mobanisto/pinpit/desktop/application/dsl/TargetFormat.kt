/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.dsl

import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.OS.Linux
import de.mobanisto.pinpit.desktop.application.internal.OS.MacOS
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.currentOS
import java.io.Serializable

enum class ArchiveFormat(val extension: String) {
    TarGz("tar.gz"),
    Zip("zip");
}

sealed class TargetFormat(val targetOS: OS) : Serializable {

    class AppImage(os: OS) : TargetFormat(os)
    class DistributableArchive(os: OS, val archiveFormat: ArchiveFormat) : TargetFormat(os)
    class Deb : TargetFormat(Linux)
    class Rpm : TargetFormat(Linux)
    class Dmg : TargetFormat(MacOS)
    class Pkg : TargetFormat(MacOS)
    class Exe : TargetFormat(Windows)
    class Msi : TargetFormat(Windows)

    val isCompatibleWithCurrentOS: Boolean by lazy { isCompatibleWith(currentOS) }

    internal fun isCompatibleWith(os: OS): Boolean = os == targetOS

    val outputDirName: String
        get() = when (this) {
            is AppImage -> "app"
            is DistributableArchive -> archiveFormat.extension
            is Deb -> "deb"
            is Rpm -> "rpm"
            is Dmg -> "dmg"
            is Pkg -> "pkg"
            is Exe -> "exe"
            is Msi -> "msi"
        }

    val fileExt: String
        get() {
            return when (this) {
                is AppImage -> throw IllegalStateException("AppImage does not have a file extension")
                is DistributableArchive -> archiveFormat.extension
                is Deb -> ".deb"
                is Rpm -> ".rpm"
                is Dmg -> ".dmg"
                is Pkg -> ".pkg"
                is Exe -> ".exe"
                is Msi -> ".msi"
            }
        }
}
