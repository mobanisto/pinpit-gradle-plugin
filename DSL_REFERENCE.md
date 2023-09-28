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
* `debPackageVersion`: File - fourth part for name of generated `.deb` files.
* `debPreInst`: File - custom pre-installation script to use
* `debPostInst`: File - custom post-installation script to use
* `debPreRm`: File - custom pre-removal script to use
* `debPostRm`: File - custom post-removal script to use
* `debLauncher`: File - custom launcher file to include

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
