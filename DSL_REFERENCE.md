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

Windows options go into a `linux` block:
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
