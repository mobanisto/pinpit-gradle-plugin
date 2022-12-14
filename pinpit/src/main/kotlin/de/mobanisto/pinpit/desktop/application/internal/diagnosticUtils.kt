/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import java.io.PrintWriter
import java.io.StringWriter

internal fun Exception.stacktraceToString(): String =
    StringWriter().also { w ->
        PrintWriter(w).use { pw -> printStackTrace(pw) }
    }.toString()
