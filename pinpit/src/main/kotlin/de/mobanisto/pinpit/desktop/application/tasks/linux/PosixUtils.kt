/*
 * Copyright 2022 Mobanisto UG (haftungsbeschraenkt) and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks.linux

import de.mobanisto.pinpit.desktop.application.internal.currentOS
import de.mobanisto.pinpit.desktop.application.internal.isUnix
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission

object PosixUtils {

    internal fun Path.createDirectories(permissions: FileAttribute<Set<PosixFilePermission>>) {
        if (currentOS.isUnix()) {
            Files.createDirectories(this, permissions)
        } else {
            Files.createDirectories(this)
        }
    }

    internal fun Path.setPosixFilePermissions(permissions: Set<PosixFilePermission>) {
        if (currentOS.isUnix()) {
            Files.setPosixFilePermissions(this, permissions)
        }
    }

}