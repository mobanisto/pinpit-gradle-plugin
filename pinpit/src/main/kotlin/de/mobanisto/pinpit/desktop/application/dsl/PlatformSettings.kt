/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.dsl

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

abstract class AbstractPlatformSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory
}

abstract class AbstractBasePlatformSettings : AbstractPlatformSettings() {
    val iconFile: RegularFileProperty = objects.fileProperty()
    var packageVersion: String? = null
}

abstract class AbstractMacOSPlatformSettings : AbstractBasePlatformSettings() {
    var packageName: String? = null

    var packageBuildVersion: String? = null
    var dmgPackageVersion: String? = null
    var dmgPackageBuildVersion: String? = null
    var appCategory: String? = null

    /**
     * An application's unique identifier across Apple's ecosystem.
     *
     * May only contain alphanumeric characters (A-Z,a-z,0-9), hyphen (-) and period (.) characters
     *
     * Use of a reverse DNS notation (e.g. com.mycompany.myapp) is recommended.
     */
    var bundleID: String? = null

    val signing: MacOSSigningSettings = objects.newInstance(MacOSSigningSettings::class.java)
    fun signing(fn: Action<MacOSSigningSettings>) {
        fn.execute(signing)
    }

    val notarization: MacOSNotarizationSettings = objects.newInstance(MacOSNotarizationSettings::class.java)
    fun notarization(fn: Action<MacOSNotarizationSettings>) {
        fn.execute(notarization)
    }
}

abstract class NativeApplicationMacOSPlatformSettings : AbstractMacOSPlatformSettings()

abstract class JvmMacOSPlatformSettings : AbstractMacOSPlatformSettings() {
    var dockName: String? = null
    var setDockNameSameAsPackageName: Boolean = true
    var appStore: Boolean = false
    var entitlementsFile: RegularFileProperty = objects.fileProperty()
    var runtimeEntitlementsFile: RegularFileProperty = objects.fileProperty()
    var pkgPackageVersion: String? = null
    var pkgPackageBuildVersion: String? = null

    val provisioningProfile: RegularFileProperty = objects.fileProperty()
    val runtimeProvisioningProfile: RegularFileProperty = objects.fileProperty()

    internal val infoPlistSettings = InfoPlistSettings()
    fun infoPlist(fn: Action<InfoPlistSettings>) {
        fn.execute(infoPlistSettings)
    }

    val distributableArchives: MutableList<DistributableArchiveSettings> = arrayListOf()
    open fun distributableArchive(fn: Action<DistributableArchiveSettings>) {
        val distributableArchive = objects.newInstance(DistributableArchiveSettings::class.java).also {
            distributableArchives.add(it)
        }
        fn.execute(distributableArchive)
    }
}

open class InfoPlistSettings {
    var extraKeysRawXml: String? = null
}

abstract class LinuxPlatformSettings : AbstractBasePlatformSettings() {
    var installationPath: String? = null
    var shortcut: Boolean = false
    var packageName: String? = null
    var appRelease: String? = null
    var appCategory: String? = null
    var debMaintainer: String? = null
    var menuGroup: String? = null
    var rpmLicenseType: String? = null
    var debPackageVersion: String? = null
    var rpmPackageVersion: String? = null
    val debPreInst: RegularFileProperty = objects.fileProperty()
    val debPostInst: RegularFileProperty = objects.fileProperty()
    val debPreRm: RegularFileProperty = objects.fileProperty()
    val debPostRm: RegularFileProperty = objects.fileProperty()

    val debCopyright: RegularFileProperty = objects.fileProperty()
    val debLauncher: RegularFileProperty = objects.fileProperty()

//    val debContainer = objects.domainObjectContainer(DebEnvironment::class.java) { name ->
//        objects.newInstance(DebEnvironment::class.java, name)
//    }

    val distributableArchives: MutableList<DistributableArchiveSettings> = arrayListOf()
    open fun distributableArchive(fn: Action<DistributableArchiveSettings>) {
        val distributableArchive = objects.newInstance(DistributableArchiveSettings::class.java).also {
            distributableArchives.add(it)
        }
        fn.execute(distributableArchive)
    }

    val debs: MutableList<DebianPlatformSettings> = arrayListOf()
    open fun deb(name: String, fn: Action<DebianPlatformSettings>) {
        val deb = objects.newInstance(DebianPlatformSettings::class.java).also {
            debs.add(it)
            it.distro = name
        }
        fn.execute(deb)
    }
}

abstract class DistributableArchiveSettings : AbstractPlatformSettings() {
    var arch: String? = null
    var format: String? = null
}

abstract class DebianPlatformSettings : AbstractPlatformSettings() {
    var distro: String? = null
    var arch: String? = null
    var qualifier: String? = null
    var depends = arrayListOf<String>()
    fun depends(vararg depends: String) {
        this.depends.addAll(depends.toList())
    }
}

abstract class WindowsPlatformSettings : AbstractBasePlatformSettings() {
    var console: Boolean = false
    var dirChooser: Boolean = true
    var perUserInstall: Boolean = false
    var shortcut: Boolean = false
    var menuGroup: String? = null
    var upgradeUuid: String? = null
    var aumid: String? = null
    var mainExeFileDescription: String? = null
    var msiPackageVersion: String? = null
    var exePackageVersion: String? = null

    val distributableArchives: MutableList<DistributableArchiveSettings> = arrayListOf()
    open fun distributableArchive(fn: Action<DistributableArchiveSettings>) {
        val distributableArchive = objects.newInstance(DistributableArchiveSettings::class.java).also {
            distributableArchives.add(it)
        }
        fn.execute(distributableArchive)
    }

    val msis: MutableList<MsiPlatformSettings> = arrayListOf()
    open fun msi(fn: Action<MsiPlatformSettings>) {
        val msi = objects.newInstance(MsiPlatformSettings::class.java).also {
            msis.add(it)
        }
        fn.execute(msi)
    }
}

abstract class MsiPlatformSettings : AbstractPlatformSettings() {
    var arch: String? = null
    val bitmapBanner: RegularFileProperty = objects.fileProperty()
    val bitmapDialog: RegularFileProperty = objects.fileProperty()
}
