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
    implementation(project(":simple-compose"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    currentOs(compose.desktop.currentOs)
    windowsX64(compose.desktop.windows_x64)
    linuxX64(compose.desktop.linux_x64)
}

val versionCode by extra("1.0.0")

pinpit.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            jvmVendor = "adoptium"
            jvmVersion = "17.0.5+8"

            packageName = "TestPackage"
            packageVersion = versionCode
            description = "Test description"
            vendor = "Test Vendor"
            copyright = "Test Copyright Holder"

            linux {
                packageName = "test-package"
                debMaintainer = "example@example.org"
                debPackageVersion = versionCode
                appCategory = "utils"
                menuGroup = "System;Utility;"
                iconFile.set(project.file("src/main/packaging/deb/hello.png"))
                deb("UbuntuFocalX64") {
                    qualifier = "ubuntu-20.04"
                    arch = "x64"
                    depends(
                        "libc6", "libexpat1", "libgcc-s1", "libpcre3", "libuuid1", "xdg-utils",
                        "zlib1g", "libnotify4"
                    )
                }
            }
            windows {
                dirChooser = true
                shortcut = true
                upgradeUuid = "2d6ff464-75be-40ad-a256-56420b9cc374"
                packageVersion = versionCode
                iconFile.set(project.file("src/main/packaging/windows/hello.ico"))
                msi {
                    arch = "x64"
                    bitmapBanner.set(project.file("src/main/packaging/windows/banner.bmp"))
                    bitmapDialog.set(project.file("src/main/packaging/windows/dialog.bmp"))
                }
            }
        }
    }
}

