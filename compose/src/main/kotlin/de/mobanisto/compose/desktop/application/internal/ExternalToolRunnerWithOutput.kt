/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.desktop.application.internal

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

internal class ExternalToolRunnerWithOutput(
    private val execOperations: ExecOperations
) {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    operator fun invoke(
        tool: File,
        args: Collection<String>,
        environment: Map<String, Any> = emptyMap(),
        workingDir: File? = null,
    ): Result {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = stdout.use {
            stderr.use {
                execOperations.exec { spec ->
                    spec.executable = tool.absolutePath
                    spec.args(*args.toTypedArray())
                    workingDir?.let { wd -> spec.workingDir(wd) }
                    spec.environment(environment)
                    // check exit value later
                    spec.isIgnoreExitValue = true

                    spec.standardOutput = stdout
                    spec.errorOutput = stderr
                }
            }
        }

        return Result(result.exitValue, String(stdout.toByteArray()), String(stderr.toByteArray()))
    }
}
