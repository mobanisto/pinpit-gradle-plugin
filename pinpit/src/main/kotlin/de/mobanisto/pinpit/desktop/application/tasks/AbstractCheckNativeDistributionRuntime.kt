/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.tasks

import de.mobanisto.pinpit.desktop.application.internal.JvmRuntimeProperties
import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.OS.MacOS
import de.mobanisto.pinpit.desktop.application.internal.executableName
import de.mobanisto.pinpit.desktop.application.internal.ioFile
import de.mobanisto.pinpit.desktop.application.internal.notNullProperty
import de.mobanisto.pinpit.desktop.tasks.AbstractPinpitTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

// __COMPOSE_NATIVE_DISTRIBUTIONS_MIN_JAVA_VERSION__
internal const val MIN_JAVA_RUNTIME_VERSION = 11

@CacheableTask
abstract class AbstractCheckNativeDistributionRuntime : AbstractPinpitTask() {
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:InputDirectory
    val javaHome: Property<String> = objects.notNullProperty()

    @Internal
    val jdk: Property<Path> = objects.notNullProperty()

    @Internal
    val targetJdkVersion: Property<Int> = objects.notNullProperty()

    @Internal
    val os: Property<OS> = objects.notNullProperty()

    private val taskDir = project.layout.buildDirectory.dir("pinpit/tmp/$name")

    @get:OutputFile
    val javaRuntimePropertiesFile: Provider<RegularFile> = taskDir.map { it.file("properties.bin") }

    @get:LocalState
    val workingDir: Provider<Directory> = taskDir.map { it.dir("localState") }

    private val javaExec: File
        get() = getTool("java")

    private val javacExec: File
        get() = getTool("javac")

    private fun getTool(toolName: String): File {
        val javaHomeBin = File(javaHome.get()).resolve("bin")
        val tool = javaHomeBin.resolve(executableName(toolName))
        check(tool.exists()) { "Could not find $tool at: ${tool.absolutePath}}" }
        return tool
    }

    @TaskAction
    fun run() {
        taskDir.ioFile.mkdirs()
        val modules = arrayListOf<String>()

        val javaRuntimeVersion = try {
            getJavaRuntimeVersionUnsafe()?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not infer Java runtime version for Java home directory: ${javaHome.get()}", e
            )
        }

        check(javaRuntimeVersion >= MIN_JAVA_RUNTIME_VERSION) {
            """|Packaging native distributions requires JDK runtime version >= $MIN_JAVA_RUNTIME_VERSION
               |Actual version: '${javaRuntimeVersion ?: "<unknown>"}'
               |Java home: ${javaHome.get()}
            """.trimMargin()
        }

        val targetJdk = targetJdkVersion.get()
        check(javaRuntimeVersion >= targetJdk) {
            """|The JDK you would like to package is a JDK $targetJdk but you build JDK is only $javaRuntimeVersion
               |Java home: ${javaHome.get()}
               |Please use a JDK with >= $targetJdk for building.
            """.trimMargin()
        }

        val dirJdk = jdk.get()
        val dirJmods = if (os.get() == MacOS) {
            val home = dirJdk.resolve("Contents/Home")
            home.resolve("jmods")
        } else {
            dirJdk.resolve("jmods")
        }
        Files.list(dirJmods).forEach { file ->
            val moduleName = file.fileName.toString().trim().substringBefore(".jmod")
            if (moduleName.isNotBlank()) {
                modules.add(moduleName)
            }
        }

        val properties = JvmRuntimeProperties(modules)
        JvmRuntimeProperties.writeToFile(properties, javaRuntimePropertiesFile.ioFile)
    }

    private fun getJavaRuntimeVersionUnsafe(): String? {
        cleanDirs(workingDir)
        val workingDir = workingDir.ioFile

        val printJavaRuntimeClassName = "PrintJavaRuntimeVersion"
        val javaVersionPrefix = "Java runtime version = '"
        val javaVersionSuffix = "'"
        val printJavaRuntimeJava = workingDir.resolve("java/$printJavaRuntimeClassName.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                import java.lang.reflect.Method;
                public class $printJavaRuntimeClassName {
                    public static void main(String[] args) {
                        Class<Runtime> runtimeClass = Runtime.class;
                        try {
                            Method version = runtimeClass.getMethod("version");
                            Object runtimeVer = version.invoke(runtimeClass);
                            Class<? extends Object> runtimeVerClass = runtimeVer.getClass();
                            try {
                                int feature = (int) runtimeVerClass.getMethod("feature").invoke(runtimeVer);
                                printVersionAndHalt((Integer.valueOf(feature)).toString());
                            } catch (NoSuchMethodException e) {
                                int major = (int) runtimeVerClass.getMethod("major").invoke(runtimeVer);
                                printVersionAndHalt((Integer.valueOf(major)).toString());
                            }
                        } catch (Exception e) {
                            printVersionAndHalt(System.getProperty("java.version"));
                        }
                    }
                    private static void printVersionAndHalt(String version) {
                        System.out.println("$javaVersionPrefix" + version + "$javaVersionSuffix");
                        Runtime.getRuntime().exit(0);
                    }
                }
                """.trimIndent()
            )
        }
        val classFilesDir = workingDir.resolve("out-classes")
        runExternalTool(
            tool = javacExec,
            args = listOf(
                "-source", "1.8",
                "-target", "1.8",
                "-d", classFilesDir.absolutePath,
                printJavaRuntimeJava.absolutePath
            )
        )

        var javaRuntimeVersion: String? = null
        runExternalTool(
            tool = javaExec,
            args = listOf("-cp", classFilesDir.absolutePath, printJavaRuntimeClassName),
            processStdout = { stdout ->
                val m = "$javaVersionPrefix(.+)$javaVersionSuffix".toRegex().find(stdout)
                javaRuntimeVersion = m?.groupValues?.get(1)
            }
        )
        return javaRuntimeVersion
    }
}
