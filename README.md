# pinpit - Platform Independent Native Packaging and Installer Toolkit

pinpit is the **P**latform **I**ndependent **N**ative **P**ackaging and
**I**nstaller **T**oolkit.
It helps you distribute JVM applications to users of the operating systems
Linux, Windows and macOS without the need to run the build on machines
with the respective operating systems installed.
This makes it possible to build your packages and installers on a plain
old Linux box and also on a CI machine.

It comes as a Gradle plugin and is intended to work for all kinds of
applications and UI toolkits, however focus is currently put on
Swing and [Compose for Desktop](https://github.com/JetBrains/compose-jb)
as UI toolkits.

pinpit is based on the Compose plugin developed by Jetbrains that lives in
the [JetBrains/compose-jb](https://github.com/JetBrains/compose-jb)
repository.
We found a bunch of shortcomings while working on some Compose-based
applications and decided to create a fork detached from the rest of the
Compose project. It still relies on the Compose plugin for managing the
Compose dependencies but introduces its own Gradle tasks for packaging.

Notable differences:

* **Cross-platform packaging.** It is our plan to support packaging at least
  from Linux-based host to target all three desktop operating systems and
  their variants.
  It's looking promising so far and it's possible we might also be able to
  implement this ability for all three host systems.
  Focus is to implement this for Linux first as this is the most-convenient
  OS that runs on our CI servers anyway and we'd like them to build our
  packages for all systems.
  The original Compose plugin is based on JPackage that ships with the JDK
  since version 14. Cross compilation
  [is a non-goal](https://openjdk.org/jeps/343#Non-Goals) for JPackage,
  so it doesn't seem likely to change in the near future towards support for
  this.
* **Improved customization.** While packaing our first applications, we
  noticed some things were not customizable, such as
  [the images used in the Windows installers](https://wixtoolset.org/docs/v3/wixui/wixui_customizations/#replacing-the-default-bitmaps)
  or some specific parameters for the start menu shortcuts like adding an
  [AUMID](https://learn.microsoft.com/en-us/windows/win32/shell/appids).
  Some of those features are not supported by the Compose plugin, others
  are not even supported by JPackage. Proposing changes for JPackage is
  probably not going to yield quick results.
  Hence the work that JPackage usually does is
  mostly replaced by custom code that emulates its behavior and where
  relevant improves on it and offers better configurability.

## Usage

Here are a few excerpts from a working project's `build.gradle.kts` file
as an example on how to configure the plugin.
For full examples, head over to
[mobanisto/pinpit-gradle-examples](https://github.com/mobanisto/pinpit-gradle-examples).

Apply the plugin like this:
```kotlin
plugins {
    id("de.mobanisto.pinpit")
}
```
The exact version of the plugin is determined by the `pluginManagement`
section in file `settings.gradle.kts`.

We need to create configurations with their own set of dependencies for each
of the target platforms:
```kotlin
val attributeUsage = Attribute.of("org.gradle.usage", String::class.java)

val currentOs: Configuration by configurations.creating {
    extendsFrom(configurations.implementation.get())
    attributes { attribute(attributeUsage, "java-runtime") }
}

val windowsX64: Configuration by configurations.creating {
    extendsFrom(configurations.implementation.get())
    attributes { attribute(attributeUsage, "java-runtime") }
}

val linuxX64: Configuration by configurations.creating {
    extendsFrom(configurations.implementation.get())
    attributes { attribute(attributeUsage, "java-runtime") }
}
```

The main source set is associated with the `currentOs` configuration so
that we can run the code with the current system and architecture's
classpath from the IDE and the CLI:
```kotlin
sourceSets {
    main {
        java {
            compileClasspath = currentOs
            runtimeClasspath = currentOs
        }
    }
}
```

When using pinpit with Compose, we should then declare the correct Compose
dependency for each of the configurations:
```kotlin
dependencies {
    implementation("com.google.guava:guava:19.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    currentOs(compose.desktop.currentOs)
    windowsX64(compose.desktop.windows_x64)
    linuxX64(compose.desktop.linux_x64)
}
```

We can then configure the basic publication preferences. Observe that
we need to specify the JVM vendor and version that we want to build
the package bundles from:
```kotlin
val versionCode by extra("1.0.0")

pinpit.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            jvmVendor = "adoptium"
            jvmVersion = "17.0.5+8"

            packageVersion = versionCode
            packageName = "PinpitComposeHelloWorld"
            description = "Test description"
            copyright = "Test Copyright Holder"
            vendor = "Test Vendor"
        }
    }
}
```

We can then go on to specify the Linux-specific options as well as defining
all the classes of Debian-based distributions that we would like to
create a specific `.deb` package for:
```kotlin
            linux {
                shortcut = true
                packageName = "pinpit-compose-hello-world"
                debMaintainer = "example@example.com"
                menuGroup = "pinpit"
                debPackageVersion = versionCode
                appCategory = "utils"
                menuGroup = "System;Utility"
                iconFile.set(project.file("src/main/resources/images/logo_circle.png"))
                debPreInst.set(project.file("src/main/packaging/deb/preinst"))
                debPostInst.set(project.file("src/main/packaging/deb/postinst"))
                debPreRm.set(project.file("src/main/packaging/deb/prerm"))
                debPostRm.set(project.file("src/main/packaging/deb/postrm"))
                debCopyright.set(project.file("src/main/packaging/deb/copyright"))
                debLauncher.set(project.file("src/main/packaging/deb/launcher.desktop"))
                deb("UbuntuFocalX64") {
                    qualifier = "ubuntu-20.04"
                    arch = "x64"
                    depends(
                        "libc6", "libexpat1", "libgcc-s1", "libpcre3", "libuuid1", "xdg-utils",
                        "zlib1g", "libnotify4"
                    )
                }
                deb("UbuntuBionicX64") {
                    qualifier = "ubuntu-18.04"
                    arch = "x64"
                    depends(
                        "libasound2", "libc6", "libexpat1", "libfontconfig1", "libfreetype6",
                        "libgcc1", "libglib2.0-0", "libgraphite2-3", "libharfbuzz0b",
                        "libjpeg-turbo8", "liblcms2-2", "libpcre3", "libpng16-16", "libstdc++6",
                        "xdg-utils", "zlib1g", "libnotify4"
                    )
                }
            }
```

Let's also define the Windows-specific options in order to package an MSI
installer:
```kotlin
            windows {
                console = true
                dirChooser = true
                perUserInstall = true
                shortcut = true
                menu = true
                menuGroup = "pinpit"
                upgradeUuid = "96D463E4-3317-44DA-88CD-AD61B845023F"
                packageVersion = versionCode
                msi {
                    arch = "x64"
                    bitmapBanner.set(project.file("src/main/packaging/windows/banner.bmp"))
                    bitmapDialog.set(project.file("src/main/packaging/windows/dialog.bmp"))
                    icon.set(project.file("src/main/packaging/windows/hello.ico"))
                }
            }
```

## Development and publishing notes

The build can be customized using environment variables. Under normal
circumstances the values from `gradle.properties` will be used but can
be overriden with the environment variables.
* `PINPIT_GRADLE_PLUGIN_VERSION` - version of plugin for publishing to
  Maven (overrides `deploy.version`).
* `COMPOSE_GRADLE_PLUGIN_COMPOSE_VERSION` - version of JetBrains Compose
  used by the plugin (overrides `compose.version`).

For example, to publish a snapshot version to Maven local, you can use this:

```
PINPIT_GRADLE_PLUGIN_VERSION=0.X.0-SNAPSHOT ./gradlew publishToMavenLocal
```

## Undocumented options

See `ComposeProjectProperties.kt` for flags and switches that can be passed
to the plugin via Gradle project properties (`-P` flags).
