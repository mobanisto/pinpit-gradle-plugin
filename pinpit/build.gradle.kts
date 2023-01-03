import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradle.plugin-publish")
    id("java-gradle-plugin")
    id("maven-publish")
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

gradlePluginConfig {
    pluginId = "de.mobanisto.pinpit"
    implementationClass = "de.mobanisto.pinpit.PinpitPlugin"
    pluginPortalTags = listOf("ui-framework")
}

mavenPublicationConfig {
    displayName = "Pinpit Gradle Plugin"
    description = "Pinpit Gradle plugin for platform independent distribution"
    artifactId = "pinpit-gradle-plugin"
}

val buildConfigDir
    get() = project.layout.buildDirectory.dir("generated/buildconfig")
val buildConfig = tasks.register("buildConfig", GenerateBuildConfig::class.java) {
    classFqName.set("de.mobanisto.pinpit.PinpitBuildConfig")
    generatedOutputDir.set(buildConfigDir)
    fieldsToGenerate.put("composeVersion", BuildProperties.composeVersion(project))
    fieldsToGenerate.put("composeGradlePluginVersion", BuildProperties.deployVersion(project))
}
tasks.named("compileKotlin") {
    dependsOn(buildConfig)
}
sourceSets.main.configure {
    java.srcDir(buildConfigDir)
}

val embeddedDependencies by configurations.creating {
    isTransitive = false
}

dependencies {
    // By default, Gradle resolves plugins only via Gradle Plugin Portal.
    // To avoid declaring an additional repo, all dependencies must:
    // 1. Either be provided by Gradle at runtime (e.g. gradleApi());
    // 2. Or be included and optionally relocated.
    // Use `embedded` helper to include a dependency.
    fun embedded(dep: Any) {
        compileOnly(dep)
        testImplementation(dep)
        embeddedDependencies(dep)
    }

    compileOnly(gradleApi())
    compileOnly(localGroovy())
    compileOnly(kotlin("gradle-plugin-api"))
    compileOnly(kotlin("gradle-plugin"))
    compileOnly(kotlin("native-utils"))

    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.7.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("gradle-plugin-api"))
    testImplementation("commons-codec:commons-codec:1.15")
    testImplementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // include relocated download task to avoid potential runtime conflicts
    embedded("de.undercouch:gradle-download-task:4.1.1")

    embedded("org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildProperties.serializationVersion}")
    embedded("org.jetbrains.kotlinx:kotlinx-serialization-core:${BuildProperties.serializationVersion}")
    embedded("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:${BuildProperties.serializationVersion}")
    embedded("org.tukaani:xz:1.9")
    embedded("org.apache.commons:commons-compress:1.22")
}

val shadow = tasks.named<ShadowJar>("shadowJar") {
    for (fromPackage in listOf("de.undercouch", "org.apache.commons.compress", "org.tukaani.xz")) {
        relocate(fromPackage, "de.mobanisto.pinpit.$fromPackage")
    }
    archiveClassifier.set("shadow")
    configurations = listOf(embeddedDependencies)
    exclude("META-INF/gradle-plugins/de.undercouch.download.properties", "META-INF/versions/**")
}

val jar = tasks.named<Jar>("jar") {
    dependsOn(shadow)
    from(zipTree(shadow.get().archiveFile))
    this.duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// __SUPPORTED_GRADLE_VERSIONS__
//testGradleVersion("6.7.1") // min supported by kotlin 1.7.0 gradle plugin https://kotlinlang.org/docs/gradle.html
// despite that, some tests didn't pass
//testGradleVersion("7.1.1")
testGradleVersion("7.3.3")

val javaHomeForTests: String? = when {
    // __COMPOSE_NATIVE_DISTRIBUTIONS_MIN_JAVA_VERSION__
    JavaVersion.current() >= JavaVersion.VERSION_15 -> System.getProperty("java.home")
    else -> System.getenv("JDK_15")
        ?: System.getenv("JDK_FOR_GRADLE_TESTS")
}
val isWindows = getCurrentOperatingSystem().isWindows

val gradleTestsPattern = "de.mobanisto.pinpit.test.tests.integration.*"

// check we don't accidentally including unexpected classes (e.g. from embedded dependencies)
val checkJar by tasks.registering {
    dependsOn(jar)

    doLast {
        val file = jar.get().archiveFile.get().asFile
        ZipFile(file).use { zip ->
            checkJarContainsExpectedPackages(zip)
        }
    }
}

// we want to avoid accidentally including unexpected jars/packages, e.g kotlin-stdlib etc
fun checkJarContainsExpectedPackages(jar: ZipFile) {
    val expectedPackages = arrayOf(
        "kotlinx/serialization",
        "de/mobanisto/pinpit",
    )
    val unexpectedClasses = arrayListOf<String>()

    for (entry in jar.entries()) {
        if (entry.isDirectory || !entry.name.endsWith(".class")) continue

        if (expectedPackages.none { prefix -> entry.name.startsWith(prefix) }) {
            unexpectedClasses.add(entry.name)
        }
    }

    if (unexpectedClasses.any()) {
        error(buildString {
            appendLine("Some classes from ${jar.name} are not from 'de.mobanisto.pinpit' package:")
            unexpectedClasses.forEach {
                appendLine("  * $it")
            }
        })
    }
}

tasks.check {
    dependsOn(checkJar)
}

tasks.test {
    dependsOn(jar)
    classpath = project.files(jar.map { it.archiveFile }) + classpath
    filter {
        excludeTestsMatching(gradleTestsPattern)
    }
}
fun testGradleVersion(gradleVersion: String) {
    val taskProvider = tasks.register("testGradle-$gradleVersion", Test::class) {
        tasks.test.get().let { defaultTest ->
            classpath = defaultTest.classpath
        }
        systemProperty("compose.tests.gradle.version", gradleVersion)
        filter {
            includeTestsMatching(gradleTestsPattern)
        }
    }
    tasks.named("check") {
        dependsOn(taskProvider)
    }
}

configureJUnit()

tasks.withType<Test>().configureEach {
    configureJavaForComposeTest()

    dependsOn(":publishToMavenLocal")

    systemProperty("compose.tests.compose.gradle.plugin.version", "1.2.1")
    systemProperty("compose.tests.pinpit.gradle.plugin.version", BuildProperties.deployVersion(project))
    systemProperty("compose.tests.pinpit.jvm.vendor", "adoptium")
    systemProperty("compose.tests.pinpit.jvm.version", "17.0.5+8")
    for ((k, v) in project.properties) {
        if (k.startsWith("compose.")) {
            systemProperty(k, v.toString())
        }
    }
}

task("printAllAndroidxReplacements") {
    doLast { printAllAndroidxReplacements() }
}
