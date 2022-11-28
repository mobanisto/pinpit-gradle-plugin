/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

import org.gradle.api.Project

// "Global" properties
object BuildProperties {
    const val group = "de.mobanisto.pinpit"
    const val website = "https://www.mobanisto.de"
    const val vcs = "https://github.com/mobanisto/pinpit-gradle-plugin"
    const val serializationVersion = "1.2.1"
    fun composeVersion(project: Project): String =
        System.getenv("PINPIT_GRADLE_PLUGIN_COMPOSE_VERSION")
            ?: project.findProperty("compose.version") as String
    fun testsAndroidxCompilerVersion(project: Project): String =
        project.findProperty("compose.tests.androidx.compiler.version") as String
    fun testsAndroidxCompilerCompatibleVersion(project: Project): String =
        project.findProperty("compose.tests.androidx.compatible.kotlin.version") as String
    fun deployVersion(project: Project): String =
        System.getenv("PINPIT_GRADLE_PLUGIN_VERSION")
            ?: project.findProperty("deploy.version") as String
}
