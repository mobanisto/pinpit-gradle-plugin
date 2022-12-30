package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.test.utils.TestProject
import de.mobanisto.pinpit.test.utils.checkContains
import de.mobanisto.pinpit.test.utils.checkContainsNot
import de.mobanisto.pinpit.test.utils.checkExists
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions
import java.util.jar.JarFile

object TestUtils {

    internal fun TestProject.testPackageJvmDistributions() {
        testPackageDebUbuntuFocal()
        testPackageMsi()
    }

    private fun TestProject.projectName() = if (pinpitSubproject == null) "" else ":${pinpitSubproject.name}"
    private fun TestProject.projectDir() = if (pinpitSubproject == null) "" else "${pinpitSubproject.dir}/"

    internal fun TestProject.testPackageDebUbuntuFocal() {
        val project = projectName()
        val projectDir = projectDir()
        gradle("$project:pinpitPackageDefaultDebUbuntuFocalX64").build().let { result ->
            Assertions.assertEquals(
                TaskOutcome.SUCCESS,
                result.task("$project:pinpitPackageDefaultDebUbuntuFocalX64")?.outcome
            )

            val resultFile =
                file("${projectDir}build/pinpit/binaries/main-default/linux/x64/deb/test-package-ubuntu-20.04-x64-1.0.0.deb")
            resultFile.checkExists()

            // TODO: add some in-depth validation
            /*resultFile.inputStream().use { fis ->
                ValidateDeb.validate(fis)
            }*/
        }
    }

    internal fun TestProject.testPackageMsi() {
        val projectName = projectName()
        val projectDir = projectDir()
        gradle("$projectName:pinpitPackageDefaultMsiX64").build().let { result ->
            Assertions.assertEquals(
                TaskOutcome.SUCCESS, result.task("$projectName:pinpitPackageDefaultMsiX64")?.outcome
            )

            val resultFile =
                file("${projectDir}build/pinpit/binaries/main-default/windows/x64/msi/TestPackage-x64-1.0.0.msi")
            resultFile.checkExists()
        }
    }

    internal fun TestProject.testPackageUberJar(target: Target) {
        val projectName = projectName()
        val projectDir = projectDir()
        gradle("$projectName:pinpitPackageDefaultUberJarFor${target.name}").build().let { result ->
            Assertions.assertEquals(
                TaskOutcome.SUCCESS,
                result.task("$projectName:pinpitPackageDefaultUberJarFor${target.name}")?.outcome
            )

            val resultJarFile = file("${projectDir}build/pinpit/jars/TestPackage-${target.id}-1.0.0.jar")
            resultJarFile.checkExists()

            JarFile(resultJarFile).use { jar ->
                val mainClass = jar.manifest.mainAttributes.getValue("Main-Class")
                Assertions.assertEquals("MainKt", mainClass, "Unexpected main class")

                jar.entries().toList().mapTo(HashSet()) { it.name }.apply {
                    checkContains("MainKt.class", "org/jetbrains/skiko/SkiaLayer.class")
                    if (target.os == OS.Linux) {
                        checkContains("libskiko-linux-x64.so", "libskiko-linux-x64.so.sha256")
                        checkContainsNot("skiko-windows-x64.dll", "skiko-windows-x64.dll.sha256")
                    } else if (target.os == OS.Windows) {
                        checkContains("skiko-windows-x64.dll", "skiko-windows-x64.dll.sha256")
                        checkContainsNot("libskiko-linux-x64.so", "libskiko-linux-x64.so.sha256")
                    }
                }
            }
        }
    }
}
