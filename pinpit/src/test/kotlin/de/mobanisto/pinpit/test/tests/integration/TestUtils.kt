package de.mobanisto.pinpit.test.tests.integration

import de.mobanisto.pinpit.desktop.application.internal.OS
import de.mobanisto.pinpit.desktop.application.internal.Target
import de.mobanisto.pinpit.test.utils.TestProject
import de.mobanisto.pinpit.test.utils.checkContains
import de.mobanisto.pinpit.test.utils.checkContainsNot
import de.mobanisto.pinpit.test.utils.checkExists
import de.mobanisto.pinpit.validation.deb.ValidateDeb
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarFile
import kotlin.io.path.name

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

            resultFile.inputStream().use { fis ->
                ValidateDeb.validateDebContents(fis)
            }

            val dirDistributableApp = file("${projectDir}build/pinpit/binaries/main-default/linux/x64/distributableApp/")
            dirDistributableApp.checkExists()

            checkContainsSome(dirDistributableApp.toPath(), ".so")
            checkContainsNone(dirDistributableApp.toPath(), ".dll")
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

            val dirDistributableApp = file("${projectDir}build/pinpit/binaries/main-default/windows/x64/distributableApp/")
            dirDistributableApp.checkExists()

            checkContainsSome(dirDistributableApp.toPath(), ".dll")
            checkContainsNone(dirDistributableApp.toPath(), ".so")
        }
    }

    internal fun TestProject.testPackageLinuxDistributableArchive() {
        val project = projectName()
        val projectDir = projectDir()
        gradle("$project:pinpitPackageDefaultDistributableTarGzLinuxX64").build().let { result ->
            Assertions.assertEquals(
                TaskOutcome.SUCCESS,
                result.task("$project:pinpitPackageDefaultDistributableTarGzLinuxX64")?.outcome
            )

            val resultFile =
                file("${projectDir}build/pinpit/binaries/main-default/linux/x64/distributableArchive/test-package-x64-1.0.0.tar.gz")
            resultFile.checkExists()

            // TODO: add some in-depth verification similar to deb file verification
//            resultFile.inputStream().use { fis ->
//                ValidateDeb.validateDebContents(fis)
//            }

            val dirDistributableApp = file("${projectDir}build/pinpit/binaries/main-default/linux/x64/distributableApp/")
            dirDistributableApp.checkExists()

            checkContainsSome(dirDistributableApp.toPath(), ".so")
            checkContainsNone(dirDistributableApp.toPath(), ".dll")

            checkContainsSomePattern(dirDistributableApp.toPath(), "skiko-awt-.*.jar")
            checkContainsSomePattern(dirDistributableApp.toPath(), "skiko-awt-runtime-linux-x64-.*.jar")
            checkContainsNonePattern(dirDistributableApp.toPath(), "skiko-awt-runtime-windows-x64-.*.jar")
        }
    }

    internal fun TestProject.testPackageWindowsDistributableArchive() {
        val project = projectName()
        val projectDir = projectDir()
        gradle("$project:pinpitPackageDefaultDistributableZipWindowsX64").build().let { result ->
            Assertions.assertEquals(
                TaskOutcome.SUCCESS,
                result.task("$project:pinpitPackageDefaultDistributableZipWindowsX64")?.outcome
            )

            val resultFile =
                file("${projectDir}build/pinpit/binaries/main-default/windows/x64/distributableArchive/TestPackage-x64-1.0.0.zip")
            resultFile.checkExists()

            // TODO: add some in-depth verification similar to deb file verification
//            resultFile.inputStream().use { fis ->
//                ValidateDeb.validateDebContents(fis)
//            }

            val dirDistributableApp = file("${projectDir}build/pinpit/binaries/main-default/windows/x64/distributableApp/")
            dirDistributableApp.checkExists()

            checkContainsSome(dirDistributableApp.toPath(), ".dll")
            checkContainsNone(dirDistributableApp.toPath(), ".so")

            checkContainsSomePattern(dirDistributableApp.toPath(), "skiko-awt-.*.jar")
            checkContainsSomePattern(dirDistributableApp.toPath(), "skiko-awt-runtime-windows-x64-.*.jar")
            checkContainsNonePattern(dirDistributableApp.toPath(), "skiko-awt-runtime-linux-x64-.*.jar")
        }
    }

    private fun checkContainsSome(dir: Path, extension: String) {
        var found = 0
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.name.endsWith(extension)) {
                        found++
                    }
                    return CONTINUE
                }
            }
        )
        assertFalse(found == 0) {
            "Expecting to find some files with extension $extension, but found none"
        }
    }

    private fun checkContainsNone(dir: Path, extension: String) {
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    assertFalse(file.name.endsWith(extension)) {
                        "Not expecting to find files with extension $extension, but found $file"
                    }
                    return CONTINUE
                }
            }
        )
    }

    private fun checkContainsSomePattern(dir: Path, pattern: String) {
        val regex = pattern.toRegex()
        var found = 0
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (regex.matches(file.name)) {
                        found++
                    }
                    return CONTINUE
                }
            }
        )
        assertFalse(found == 0) {
            "Expecting to find some files with extension $pattern, but found none"
        }
    }

    private fun checkContainsNonePattern(dir: Path, pattern: String) {
        val regex = pattern.toRegex()
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    assertFalse(regex.matches(file.name)) {
                        "Not expecting to find files with extension $pattern, but found $file"
                    }
                    return CONTINUE
                }
            }
        )
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
