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
[mobanisto/pinpit-gradle-examples](https://github.com/mobanisto/pinpit-gradle-examples)
or learn from an existing multiplatform app that uses pinpit:
[sebkur/lanchat](https://github.com/sebkur/lanchat).
For more details, have a look at the [DSL reference](DSL_REFERENCE.md)
where we build a description of all available options offered by the DSL.

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
create a specific `.deb` package for as well as a distributable tar.gz
archive:
```kotlin
            linux {
                shortcut = true
                packageName = "pinpit-compose-hello-world"
                debMaintainer = "example@example.com"
                menuGroup = "pinpit"
                debPackageVersion = versionCode
                appCategory = "utils"
                menuGroup = "System;Utility"
                iconFile.set(project.file("src/main/packaging/deb/hello.png"))
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
                distributableArchive {
                     format = "tar.gz"
                     arch = "x64"
                }
            }
```

Let's also define the Windows-specific options in order to package an MSI
installer and a distributable zip archive:
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
                iconFile.set(project.file("src/main/packaging/windows/hello.ico"))
                msi {
                    arch = "x64"
                    bitmapBanner.set(project.file("src/main/packaging/windows/banner.bmp"))
                    bitmapDialog.set(project.file("src/main/packaging/windows/dialog.bmp"))
                }
                distributableArchive {
                     format = "zip"
                     arch = "x64"
                }
            }
```

This will add a set of tasks to your build:
```
Pinpit tasks
------------
pinpitCheckRuntimeLinuxX64 - Checks that the JDK used for building is compatible with the distribution JVM.
pinpitCheckRuntimeWindowsX64 - Checks that the JDK used for building is compatible with the distribution JVM.
pinpitCreateDefaultDistributable - Creates a directory for each system and architecture containing all files to be distributed including launcher, app and runtime image.
pinpitCreateDefaultDistributableLinuxX64 - Creates a directory for LinuxX64 containing all files to be distributed including launcher, app and runtime image.
pinpitCreateDefaultDistributableWindowsX64 - Creates a directory for WindowsX64 containing all files to be distributed including launcher, app and runtime image.
pinpitCreateDefaultRuntime - Creates a runtime image for each system and architecture using jlink.
pinpitCreateDefaultRuntimeImageLinuxX64 - Creates a runtime image from the JVM for LinuxX64 using jlink.
pinpitCreateDefaultRuntimeImageWindowsX64 - Creates a runtime image from the JVM for WindowsX64 using jlink.
pinpitDownloadJdkLinuxX64 - Downloads the JDK for LinuxX64 that is used to derive a runtime to distribute with the app.
pinpitDownloadJdkWindowsX64 - Downloads the JDK for WindowsX64 that is used to derive a runtime to distribute with the app.
pinpitPackageDefault - Builds packages for all systems and architectures.
pinpitPackageDefaultDebUbuntuBionicX64 - Builds a DEB package for LinuxX64.
pinpitPackageDefaultDebUbuntuFocalX64 - Builds a DEB package for LinuxX64.
pinpitPackageDefaultDistributableTarGzLinuxX64 - Builds a distributable TarGz archive for LinuxX64.
pinpitPackageDefaultDistributableZipWindowsX64 - Builds a distributable Zip archive for WindowsX64.
pinpitPackageDefaultMsiX64 - Builds an MSI package for WindowsX64.
pinpitPackageDefaultUberJar - Packages an Uber-Jar for each system and architecture.
pinpitPackageDefaultUberJarForLinuxX64 - Packages an Uber-Jar for LinuxX64.
pinpitPackageDefaultUberJarForWindowsX64 - Packages an Uber-Jar for WindowsX64.
pinpitPrepareAppResourcesLinuxX64 - Merge all app resources for LinuxX64 into a single build directory.
pinpitPrepareAppResourcesWindowsX64 - Merge all app resources for WindowsX64 into a single build directory.
pinpitRun - Runs the application.
pinpitRunDefaultDistributableLinuxX64 - Runs the app from the created distributable directory for LinuxX64.
pinpitRunDefaultDistributableWindowsX64 - Runs the app from the created distributable directory for WindowsX64.
pinpitSuggestDebDependencies - Suggests Debian package dependencies to use for the current OS using dpkg.
pinpitSuggestRuntimeModulesLinuxX64 - Suggests JVM modules to include for the distribution using jdeps.
pinpitSuggestRuntimeModulesWindowsX64 - Suggests JVM modules to include for the distribution using jdeps.
pinpitUnpackDefaultComposeDesktopJvmApplicationResources - Unpacks the default Compose resources such as launcher icons.
```

For example you can create the Debian package for Ubuntu Focal using:
```
./gradlew pinpitPackageDefaultDebUbuntuFocalX64
```

or build the MSI for Windows:
```
./gradlew pinpitPackageDefaultMsiX64
```

## Platform compatibility

It's currently possible to build Linux and Windows packages
cross-platform from both a Linux and a Windows host system.
All other combinations do not work yet.

One building block for bundling packages for any platform is creating
a JDK runtime image to ship with the application.
Fortunately, assembling a runtime image from a given JDK image works
cross-platform because `jlink` can assemble runtime images from JDK
images for different platforms than the build host platform.

Assembling Debian packages does no longer rely on Debian-specific native
tools (i.e. `dpkg-deb`). Instead this has been implemented using pure
JVM-based archiving tools so that building Debian is possible for all host
systems.

Assembling MSI installers uses the Wix toolchain. On Windows, this runs
natively and on Linux hosts, Wine is used to run it.
For this to work, you need to have the current stable
version of Wine installed (from the
[WineHQ download page](https://wiki.winehq.org/Download), at
the time of writing version 7.0.1).
It's also required to install [Mono](https://wiki.winehq.org/Mono) using
Wine. Download the latest MSI version from
[here](https://dl.winehq.org/wine/wine-mono/) (version 7.4.0 at the moment)
and install that using `wine msiexec /i wine-mono-7.4.0-x86.msi`.

Here's a summary of the supported build hosts and target formats and what
we plan to work on:

| Build host              | Debian/Ubuntu       | Windows | macOS   |
|:------------------------|---------------------|---------|---------|
| Target: Linux (deb)     | yes                 | yes     | planned |
| Target: Windows (MSI)   | yes (Wine required) | yes     | no      |
| Target: macOS (PKG/DMG) | planned for Q1 2023 | no      | planned |

Building Debian packages should already be possible on macOS but it has not
been tested yet.

Building MSI installers on macOS can work on systems for which Wine is
available. See https://github.com/mobanisto/pinpit-gradle-plugin/issues/11
for details.

Building macOS packages is planned to be worked on in Q1 2023. While it
should be defintely possible to re-enable this for building on macOS itself
our goal here would be to implement this for Linux-based systems in order
to be able to produce packages on the Linux-CI.

### Future work

It's also a goal of this project to implement packaging for more common
packaging formats for any of the operating systems and their distributions.

One format that seems to make sense is simple `zip` and `tar.gz` packages
that can just be unpacked on the target system and run from there.
The kind of no-install application that you can also carry on your USB drive.
It will not work for all kinds of applications (such as those which require
the installer or packaging system to do some work on the system like
setting up launchers or manipulating the registry with administrator
priviliges) but for some this will be feasible and a nice packaging
mechanism.

On Linux, RPM should probably be worked on to support Redhat Linux and
Fedora. JPackage already supports this and it would be good to find a
cross-platform solution for building RPMs like it should be possible
with Debian packages.

For Linux there are new cross-distro packaging formats that could be worked
on like [AppImage](https://appimage.org/) and
[Flatpak](https://flatpak.org/).

For Windows, there are different tools than Wix like Inno Setup or the
Nullsoft Scriptable Install System (NSIS) that could be used to build
alternative installers for Windows.

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

See `PinpitProperties.kt` for flags and switches that can be passed
to the plugin via Gradle project properties (`-P` flags).
