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

## Windows options

Windows options go into a `windows` block:
```kotlin
        nativeDistributions {
            windows{
                …
            }
        }
```

* `console`: Boolean (default: `false`) - when set to true, launching the
  app will bring up an additional console window where the user can see
  stdout. Equivalent of JPackage's `--win-console` option.

### MSI options

MSI options go into an `msi` block:
```kotlin
            windows{
                msi {
                    …
                }
            }
```

* `bitmapDialog` - a 493 x 312 pixels bmp image file used on the welcome and
  completion dialogs with the leftmost 164 pixel-wide column being visible
  (see [Wix UI Customization Guide](https://wixtoolset.org/docs/v3/wixui/wixui_customizations/#replacing-the-default-bitmaps))
* `bitmapBanner` - a 493 x 58 pixels bmp image file used as a top banner on
  other dialogs
  (see [Wix UI Customization Guide](https://wixtoolset.org/docs/v3/wixui/wixui_customizations/#replacing-the-default-bitmaps))
