# DSL Reference

## Main options

```kotlin
val versionCode by extra("1.0.0")

pinpit.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            jvmVendor = "adoptium"
            jvmVersion = "17.0.5+8"

            packageVersion = versionCode
            packageName = "Pinpit Example"
            description = "Test description"
            copyright = "Test Copyright Holder"
            vendor = "Test Vendor"
        }
    }
}
```

You can add JVM arguments like this:

```
        nativeDistributions {
            jvmArgs("-Xmx4G")
        }
```
like that, you can also specify options such as this one:

```
        nativeDistributions {
            jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        }
```

### Modules

Each JDK supports a number of modules that can be packaged into the
application. You can see the list of modules supported by your JVM by
running `java --list-modules`.

You can specify which modules to bundle with the application like this:

```
        nativeDistributions {
            modules("java.sql")
            modules("java.naming")
            …
        }
```

If you forget to bundle a required module with your application, you're
going to see exceptions on the console output when running your packaged
application.

It can be cumbersome to figure out which modules you need to include.
Pinpit can help you to figure out the modules by running
`./gradlew pinpitSuggestRuntimeModulesLinuxX64` (on Linux).

## Linux options

Linux options go into a `linux` block:
```kotlin
        nativeDistributions {
            linux {
                …
            }
        }
```

* `packageName`: String - first part for name of generated `.deb` files.
* `appCategory`: String - a section to categorize the debian package.
   See the relevant [chapter](https://www.debian.org/doc/debian-policy/ch-archive.html#sections)
   on sections in the Debian Policy Manual for details.
* `menuGroup`: String - a list of menu groups where the launcher will be
   shown, such as 'Office' or 'Office;WordProcessor'. See the relevant
   [chapter](https://specifications.freedesktop.org/menu-spec/latest/apa.html)
   of the Desktop Menu Specification for a list of possible values.
* `iconFile`: File - an icon image file for the launcher.
   Use a 500 x 500 pixels PNG image.
* `debMaintainer`: String - package maintainer email address.
* `debPackageVersion`: File - fourth part for name of generated `.deb` files.
* `debPreInst`: File - custom pre-installation script to use.
* `debPostInst`: File - custom post-installation script to use.
* `debPreRm`: File - custom pre-removal script to use.
* `debPostRm`: File - custom post-removal script to use.
* `debLauncher`: File - custom launcher file to include. By default pinpit
  will generate such as file for your app using the other values such as
  `packageName`, `packageDescription` and `menuGroup`.
  If you need to apply more specific customizations, you can supply your
  own handcrafted file here. See the
  [Desktop Entry Specification](https://specifications.freedesktop.org/desktop-entry-spec/desktop-entry-spec-latest.html)
  for details.

### DEB options

DEB options go into a `deb` block:
```kotlin
            linux {
                deb {
                    …
                }
            }
```

The package file name is constructed like this:
`${linux.packageName}-${deb.qualifier}-${deb.arch}-${linux.debPackageVersion}.deb`

* `qualifier`: String - second part for name of generated `.deb` files.
* `arch`: String - third part for name of generated `.deb` files.
* `depends`: vararg\<String> - list of system packages that the `.deb` will
  depend on (ends up in the `control` file's `Depends:` section)

### AppImage options

AppImage options go into an `appImage` block:
```kotlin
            linux {
                appImage {
                    …
                }
            }
```

The package file name is constructed like this:
`${linux.packageName}-${appImage.arch}-${packageVersion}.AppImage`

* `arch`: String - second part for name of generated `.AppImage` files.

## Windows options

Windows options go into a `windows` block:
```kotlin
        nativeDistributions {
            windows {
                …
            }
        }
```

* `console`: Boolean (default: `false`) - when set to true, launching the
  app will bring up an additional console window where the user can see
  stdout. Equivalent of JPackage's `--win-console` option.
* `upgradeUuid`: String - a GUID (MS-specific ID similar to UUID) that needs to
  remain constant for a single app across updates.
  Can be generated using online tools, but uppercased regular UUIDs also seem to work fine: `uuidgen | tr a-z A-Z`.
* `iconFile`: File - an icon image file for the launcher.
   Use an ICO image with image versions of sizes 16, 32, 48, 256.

### MSI options

MSI options go into an `msi` block:
```kotlin
            windows {
                msi {
                    …
                }
            }
```

* `bitmapDialog`: File - a 493 x 312 pixels bmp image file used on the
  welcome and completion dialogs with the leftmost 164 pixel-wide column
  being visible
  (see [Wix UI Customization Guide](https://wixtoolset.org/docs/v3/wixui/wixui_customizations/#replacing-the-default-bitmaps))
* `bitmapBanner`: Fie - a 493 x 58 pixels bmp image file used as a top
  banner on other dialogs
  (see [Wix UI Customization Guide](https://wixtoolset.org/docs/v3/wixui/wixui_customizations/#replacing-the-default-bitmaps))

## macOS options

macOS options go into a `macOS` block:
```kotlin
        nativeDistributions {
            macOS {
                …
            }
        }
```

* `bundleID`: String - a unique identifier for your app.
* `appCategory`: String - a category for your app. See the
  [reference](https://developer.apple.com/documentation/bundleresources/information_property_list/lsapplicationcategorytype)
  for possible values.
* `iconFile`: File - an icon image file for the launcher.
   Use an ICNS image that contains various image versions with different sizes.
