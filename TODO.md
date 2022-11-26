# TODO list

* [x] Use jlink on configured JVM instead of runtime one
* [x] Simulate jpackage for Linux
* [x] Simulate jpackage for Windows
* [ ] Simulate jpackage for macOS
* [x] Use correct skiko library for each platform
* [ ] Configure runDistributable to depend on current platform and execute that

## Linux

* [ ] Package RPM files
* [ ] Custom installation path (instead of /opt/package-name/)
* [ ] Ability to generate default preinst, postinst, prerm, postrm scripts
* [ ] Use default resources for debian when not configured (icon, scripts)

## Windows

* [x] Package MSI using Wix
* [ ] Use default resources for MSI when not configured (icon, banner etc.)
* [ ] Icon customization of launcher

## macOS

* [ ] Package DMG or PKG files
* [ ] Notarization etc
