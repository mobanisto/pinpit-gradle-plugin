plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("de.mobanisto.pinpit")
}

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

sourceSets {
    main {
        java {
            compileClasspath = currentOs
            runtimeClasspath = currentOs
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    currentOs(compose.desktop.currentOs)
    windowsX64(compose.desktop.windows_x64)
    linuxX64(compose.desktop.linux_x64)
}

val versionCode by extra("1.0.0")

pinpit.desktop {
    application {
        mainClass = "de.mobanisto.lanchat.LanChatKt"

        nativeDistributions {
            jvmVendor = "PINPIT_JVM_VENDOR"
            jvmVersion = "PINPIT_JVM_VERSION"

            packageVersion = versionCode
            packageName = "TestPackage"
            description = "Test description"
            copyright = "Test Copyright Holder"
            vendor = "Test Vendor"

            linux {
                shortcut = true
                packageName = "test-package"
                debMaintainer = "example@example.com"
                menuGroup = "menu-group"
                debPackageVersion = versionCode
                appCategory = "utils"
                menuGroup = "System;Utility;"
                debPreInst.set(project.file("src/main/packaging/deb/preinst"))
                debPostInst.set(project.file("src/main/packaging/deb/postinst"))
                debPreRm.set(project.file("src/main/packaging/deb/prerm"))
                debPostRm.set(project.file("src/main/packaging/deb/postrm"))
                debCopyright.set(project.file("src/main/packaging/deb/copyright"))
                debLauncher.set(project.file("src/main/packaging/deb/launcher.desktop"))
                deb("UbuntuFocalX64") {
                    qualifier = "ubuntu-20.04"
                    arch = "x64"
                    depends("libc6", "libexpat1", "libgcc-s1", "libpcre3", "libuuid1", "xdg-utils", "zlib1g", "libnotify4")
                }
                deb("UbuntuBionicX64") {
                    qualifier = "ubuntu-18.04"
                    arch = "x64"
                    depends("libasound2", "libc6", "libexpat1", "libfontconfig1", "libfreetype6", "libgcc1",
                        "libglib2.0-0", "libgraphite2-3", "libharfbuzz0b", "libjpeg-turbo8", "liblcms2-2",
                        "libpcre3", "libpng16-16", "libstdc++6", "xdg-utils", "zlib1g", "libnotify4")
                }
                deb("DebianBullseyeX64") {
                    qualifier = "debian-bullseye"
                    arch = "x64"
                    depends("libasound2", "libbrotli1", "libc6", "libexpat1", "libfontconfig1", "libfreetype6",
                        "libgcc-s1", "libglib2.0-0", "libgraphite2-3", "libharfbuzz0b", "libjpeg62-turbo",
                        "liblcms2-2", "libpcre3", "libpng16-16", "libstdc++6", "libuuid1", "xdg-utils", "zlib1g",
                        "libnotify4")
                }
                distributableArchive {
                    arch = "x64"
                    format = "tar.gz"
                }
            }
            windows {
                dirChooser = true
                perUserInstall = true
                shortcut = true
                menu = true
                menuGroup = "compose"
                upgradeUuid = "2d6ff464-75be-40ad-a256-56420b9cc374"
                packageVersion = versionCode
                iconFile.set(project.file("src/main/packaging/windows/hello.ico"))
                msi {
                    arch = "x64"
                    bitmapBanner.set(project.file("src/main/packaging/windows/banner.bmp"))
                    bitmapDialog.set(project.file("src/main/packaging/windows/dialog.bmp"))
                }
                distributableArchive {
                    arch = "x64"
                    format = "zip"
                }
            }
        }
    }
}
