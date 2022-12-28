/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.application.dsl.DebianPlatformSettings
import de.mobanisto.pinpit.desktop.application.dsl.JvmApplicationBuildType
import de.mobanisto.pinpit.desktop.application.dsl.MsiPlatformSettings
import de.mobanisto.pinpit.desktop.application.internal.OS.Linux
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.validation.validatePackageVersions
import de.mobanisto.pinpit.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import de.mobanisto.pinpit.desktop.application.tasks.AbstractJLinkTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractNotarizationTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractProguardTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractRunDistributableTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractSuggestModulesTask
import de.mobanisto.pinpit.desktop.application.tasks.AppImageTask
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import de.mobanisto.pinpit.desktop.application.tasks.DownloadJdkTask
import de.mobanisto.pinpit.desktop.application.tasks.linux.PackageDebTask
import de.mobanisto.pinpit.desktop.application.tasks.linux.SuggestDebDependenciesTask
import de.mobanisto.pinpit.desktop.application.tasks.windows.PackageMsiTask
import de.mobanisto.pinpit.desktop.application.tasks.windows.configurePeRebrander
import de.mobanisto.pinpit.desktop.application.tasks.windows.configureWix
import de.mobanisto.pinpit.desktop.tasks.AbstractUnpackDefaultComposeApplicationResourcesTask
import de.mobanisto.pinpit.internal.addUnique
import de.mobanisto.pinpit.internal.uppercaseFirstChar
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File

private val defaultJvmArgs = listOf("-D$CONFIGURE_SWING_GLOBALS=true")
internal const val pluginTaskGroup = "pinpit"

// todo: multiple launchers
// todo: file associations
// todo: use workers
internal fun JvmApplicationContext.configureJvmApplication() {
    // Derive list of targets by configured package formats
    val targets = mutableListOf<Target>()
    app.nativeDistributions.apply {
        linux.debs.forEach { targets.addUnique(Target(Linux, arch(it.arch))) }
        windows.msis.forEach { targets.addUnique(Target(Windows, arch(it.arch))) }
    }

    if (app.isDefaultConfigurationEnabled) {
        configureDefaultApp()
    }

    validatePackageVersions(targets)
    val commonTasks = configureCommonJvmDesktopTasks()
    val targetTasks = TargetTasks()
    configurePackagingTasks(targetTasks, commonTasks)
    copy(buildType = app.buildTypes.release).configurePackagingTasks(targetTasks, commonTasks)
    configureWix()
    configurePeRebrander()
}

internal data class TargetAndBuildType(val target: Target, val buildType: JvmApplicationBuildType)

internal class TargetTasks {
    val downloadJdkTasks = mutableMapOf<Target, TaskProvider<DownloadJdkTask>>()
    val checkRuntimeTasks = mutableMapOf<Target, TaskProvider<AbstractCheckNativeDistributionRuntime>>()
    val suggestModulesTasks = mutableMapOf<Target, TaskProvider<AbstractSuggestModulesTask>>()
    val proguardTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AbstractProguardTask>>()
    val runtimeTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AbstractJLinkTask>>()
    val distributableTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AppImageTask>>()
    val runTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AbstractRunDistributableTask>>()
    val suggestDebDependenciesTasks = mutableMapOf<TargetAndBuildType, TaskProvider<SuggestDebDependenciesTask>>()
}

internal class CommonJvmDesktopTasks(
    val unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
    val prepareAppResources: TaskProvider<Sync>,
)

internal class CommonJvmPackageTasks(
    val checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>,
    val runProguard: TaskProvider<AbstractProguardTask>?,
    val createRuntimeImage: TaskProvider<AbstractJLinkTask>,
)

private fun JvmApplicationContext.configureCommonJvmDesktopTasks(): CommonJvmDesktopTasks {
    val unpackDefaultResources = tasks.register<AbstractUnpackDefaultComposeApplicationResourcesTask>(
        taskNameAction = "pinpitUnpack",
        taskNameObject = "DefaultComposeDesktopJvmApplicationResources",
        useBuildTypeForTaskName = false,
        description = "Unpacks the default Compose resources such as launcher icons",
    ) {}

    val prepareAppResources = tasks.register<Sync>(
        taskNameAction = "pinpitPrepare",
        taskNameObject = "appResources",
        useBuildTypeForTaskName = false,
    ) {
        val appResourcesRootDir = app.nativeDistributions.appResourcesRootDir
        if (appResourcesRootDir.isPresent) {
            from(appResourcesRootDir.dir("common"))
            from(appResourcesRootDir.dir(currentOS.id))
            from(appResourcesRootDir.dir(currentTarget.id))
        }
        into(jvmTmpDirForTask())
    }

    return CommonJvmDesktopTasks(
        unpackDefaultResources,
        prepareAppResources,
    )
}

private fun JvmApplicationContext.configurePackagingTasks(
    targetTasks: TargetTasks,
    commonTasks: CommonJvmDesktopTasks,
) {
    if (app.nativeDistributions.jvmVendor == null) {
        throw GradleException("Please specify a JVM vendor")
    }
    if (app.nativeDistributions.jvmVersion == null) {
        throw GradleException("Please specify a JVM version")
    }
    val jdkInfo = jdkInfo(app.nativeDistributions.jvmVendor!!, app.nativeDistributions.jvmVersion!!)
        ?: throw GradleException("Invalid JVM vendor or version")

    app.nativeDistributions.windows.msis.forEach { msi ->
        val target = Target(Windows, arch(msi.arch))
        val targetBuild = TargetAndBuildType(target, buildType)

        val packageTasks = configureCommonPackageTasks(
            tasks, jdkInfo, targetBuild, app, appTmpDir, targetTasks, commonTasks
        )

        val createDistributable = targetTasks.distributableTasks[targetBuild] ?: tasks.register<AppImageTask>(
            taskNameAction = "pinpitCreate",
            taskNameObject = "distributable${target.name}",
            args = listOf(target)
        ) {
            configureAppImageTask(
                this,
                createRuntimeImage = packageTasks.createRuntimeImage,
                prepareAppResources = commonTasks.prepareAppResources,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources,
                runProguard = packageTasks.runProguard
            )
        }.also { targetTasks.distributableTasks[targetBuild] = it }

        val runDistributable = targetTasks.runTasks[targetBuild] ?: tasks.register<AbstractRunDistributableTask>(
            taskNameAction = "pinpitRun",
            taskNameObject = "distributable${target.name}",
            args = listOf(createDistributable)
        ).also { targetTasks.runTasks[targetBuild] = it }

        tasks.register<PackageMsiTask>(
            taskNameAction = "pinpitPackage",
            taskNameObject = "msi" + target.arch.id.uppercaseFirstChar(),
            args = listOf(target)
        ) {
            configureCustomPackageTask(
                this,
                createAppImage = createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
            configurePlatformSettings(
                this,
                msi = msi,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }
    }

    app.nativeDistributions.linux.debs.forEach { deb ->
        val target = Target(Linux, arch(deb.arch))
        val targetBuild = TargetAndBuildType(target, buildType)
        val distro = deb.distro!!

        val packageTasks = configureCommonPackageTasks(
            tasks, jdkInfo, targetBuild, app, appTmpDir, targetTasks, commonTasks
        )

        val createDistributable = targetTasks.distributableTasks[targetBuild] ?: tasks.register<AppImageTask>(
            taskNameAction = "pinpitCreate",
            taskNameObject = "distributable${target.name}",
            args = listOf(target)
        ) {
            configureAppImageTask(
                this,
                createRuntimeImage = packageTasks.createRuntimeImage,
                prepareAppResources = commonTasks.prepareAppResources,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources,
                runProguard = packageTasks.runProguard
            )
        }.also { targetTasks.distributableTasks[targetBuild] = it }

        val runDistributable = targetTasks.runTasks[targetBuild] ?: tasks.register<AbstractRunDistributableTask>(
            taskNameAction = "pinpitRun",
            taskNameObject = "distributable${target.name}",
            args = listOf(createDistributable)
        ).also { targetTasks.runTasks[targetBuild] = it }

        tasks.register<PackageDebTask>(
            taskNameAction = "pinpitPackage",
            taskNameObject = "deb" + distro.uppercaseFirstChar(),
            args = listOf(target, deb.qualifier!!)
        ) {
            configureCustomPackageTask(
                this,
                createAppImage = createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
            configurePlatformSettings(
                this,
                deb = deb,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }
    }

    if (buildType === app.buildTypes.default) {
        tasks.register<DefaultTask>("pinpitPackage") {
            // TODO: depend on all package tasks
            // dependsOn(packageForCurrentOS)
        }
    }

    val packageUberJarForCurrentOS = tasks.register<Jar>(
        taskNameAction = "pinpitPackage",
        taskNameObject = "uberJarForCurrentOS"
    ) {
        configurePackageUberJarForCurrentOS(this, currentOS)
    }

    if (buildType == app.buildTypes.default) {
        val run = tasks.register<JavaExec>(
            taskNameAction = "pinpitRun",
            useBuildTypeForTaskName = false
        ) {
            configureRunTask(this, commonTasks.prepareAppResources)
        }
    }

    if (currentOS == Linux) {
        val defaultTargetBuild = TargetAndBuildType(currentTarget, app.buildTypes.default)
        val createDistributable = targetTasks.distributableTasks[defaultTargetBuild]
        val suggestDebDependencies = targetTasks.suggestDebDependenciesTasks[defaultTargetBuild]
        if (createDistributable != null && suggestDebDependencies == null) {
            tasks.register<SuggestDebDependenciesTask>(
                taskNameAction = "pinpitSuggestDebDependencies",
                useBuildTypeForTaskName = false
            ) {
                dependsOn(createDistributable)
                appImage.set(createDistributable.flatMap { it.destinationDir })
            }.also { targetTasks.suggestDebDependenciesTasks[defaultTargetBuild] = it }
        }
    }
}

private fun JvmApplicationContext.configureCommonPackageTasks(
    tasks: JvmTasks,
    jdkInfo: JdkInfo,
    targetAndBuildType: TargetAndBuildType,
    app: JvmApplicationData,
    appTmpDir: Provider<Directory>,
    targetTasks: TargetTasks,
    commonTasks: CommonJvmDesktopTasks,
): CommonJvmPackageTasks {
    val target = targetAndBuildType.target

    val downloadJdk = targetTasks.downloadJdkTasks[target] ?: tasks.register<DownloadJdkTask>(
        taskNameAction = "pinpitDownload",
        taskNameObject = "jdk${target.name}",
        useBuildTypeForTaskName = false
    ) {
        jvmVendor.set(app.nativeDistributions.jvmVendor)
        jvmVersion.set(app.nativeDistributions.jvmVersion)
        this.os.set(target.os.id)
        this.arch.set(target.arch.id)
    }.also { targetTasks.downloadJdkTasks[target] = it }

    val checkRuntime = targetTasks.checkRuntimeTasks[target] ?: tasks.register<AbstractCheckNativeDistributionRuntime>(
        taskNameAction = "pinpitCheck",
        taskNameObject = "runtime${target.name}",
        useBuildTypeForTaskName = false
    ) {
        dependsOn(downloadJdk)
        targetJdkVersion.set(jdkInfo.major)
        javaHome.set(app.javaHomeProvider)
        jdk.set(provider { downloadJdk.get().jdkDir })
    }.also { targetTasks.checkRuntimeTasks[target] = it }

    val suggestRuntimeModules = targetTasks.suggestModulesTasks[target] ?: tasks.register<AbstractSuggestModulesTask>(
        taskNameAction = "pinpitSuggest",
        taskNameObject = "runtimeModules${target.name}",
        useBuildTypeForTaskName = false
    ) {
        dependsOn(checkRuntime)
        jdk.set(provider { downloadJdk.get().jdkDir })
        modules.set(provider { app.nativeDistributions.modules })

        useAppRuntimeFiles(target) { (jarFiles, mainJar) ->
            files.from(jarFiles)
            launcherMainJar.set(mainJar)
        }
    }.also { targetTasks.suggestModulesTasks[target] = it }

    val runProguard =
        targetTasks.proguardTasks[targetAndBuildType] ?: if (buildType.proguard.isEnabled.orNull == true) {
            tasks.register<AbstractProguardTask>(
                taskNameAction = "pinpitProguard",
                taskNameObject = "jars${target.name}"
            ) {
                configureProguardTask(this, target, /*targetData,*/ commonTasks.unpackDefaultResources)
            }.also { targetTasks.proguardTasks[targetAndBuildType] = it }
        } else null

    val createRuntimeImage = targetTasks.runtimeTasks[targetAndBuildType] ?: tasks.register<AbstractJLinkTask>(
        taskNameAction = "pinpitCreate",
        taskNameObject = "runtimeImage${target.name}"
    ) {
        dependsOn(checkRuntime)
        dependsOn(downloadJdk)
        jdk.set(provider { downloadJdk.get().jdkDir })
        javaHome.set(app.javaHomeProvider)
        modules.set(provider { app.nativeDistributions.modules })
        includeAllModules.set(provider { app.nativeDistributions.includeAllModules })
        javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
        destinationDir.set(appTmpDir.dir("${target.os.id}/${target.arch.id}/runtime"))
    }.also { targetTasks.runtimeTasks[targetAndBuildType] = it }

    return CommonJvmPackageTasks(checkRuntime, runProguard, createRuntimeImage)
}

private fun JvmApplicationContext.configureProguardTask(
    proguard: AbstractProguardTask,
    target: Target,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
): AbstractProguardTask = proguard.apply {
    val settings = buildType.proguard
    mainClass.set(app.mainClass)
    proguardVersion.set(settings.version)
    configurationFiles.from(settings.configurationFiles)
    // ProGuard uses -dontobfuscate option to turn off obfuscation, which is enabled by default
    // We want to disable obfuscation by default, because often
    // it is not needed, but makes troubleshooting much harder.
    // If obfuscation is turned off by default,
    // enabling (`isObfuscationEnabled.set(true)`) seems much better,
    // than disabling obfuscation disabling (`dontObfuscate.set(false)`).
    // That's why a task property is follows ProGuard design,
    // when our DSL does the opposite.
    dontobfuscate.set(settings.obfuscate.map { !it })

    dependsOn(unpackDefaultResources)
    defaultComposeRulesFile.set(unpackDefaultResources.flatMap { it.resources.defaultComposeProguardRules })

    maxHeapSize.set(settings.maxHeapSize)
    destinationDir.set(appTmpDir.dir("proguard"))
    javaHome.set(app.javaHomeProvider)

    useAppRuntimeFiles(target) { files ->
        inputFiles.from(files.allRuntimeJars)
        mainJar.set(files.mainJar)
    }
}

private fun JvmApplicationContext.configureCustomPackageTask(
    packageTask: CustomPackageTask,
    createAppImage: TaskProvider<AppImageTask>,
    checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
    runProguard: Provider<AbstractProguardTask>? = null
) {
    createAppImage.let { createAppImage ->
        packageTask.dependsOn(createAppImage)
        packageTask.appImage.set(createAppImage.flatMap { it.destinationDir })
    }

    checkRuntime.let { checkRuntime ->
        packageTask.dependsOn(checkRuntime)
        packageTask.javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
    }

    packageTask.dependsOn(unpackDefaultResources)

    app.nativeDistributions.let { executables ->
        packageTask.jvmVendor.set(provider { executables.jvmVendor })
        packageTask.jvmVersion.set(provider { executables.jvmVersion })
        packageTask.packageName.set(provider { executables.packageName })
        packageTask.packageDescription.set(packageTask.provider { executables.description })
        packageTask.packageCopyright.set(packageTask.provider { executables.copyright })
        packageTask.packageVendor.set(packageTask.provider { executables.vendor })
//        packageTask.packageVersion.set(packageVersionFor(packageTask.targetFormat))
//        packageTask.licenseFile.set(executables.licenseFile)
    }

//    packageTask.javaHome.set(app.javaHomeProvider)

    if (runProguard != null) {
        packageTask.dependsOn(runProguard)
        packageTask.files.from(project.fileTree(runProguard.flatMap { it.destinationDir }))
        packageTask.launcherMainJar.set(runProguard.flatMap { it.mainJarInDestinationDir })
        packageTask.mangleJarFilesNames.set(false)
    } else {
        packageTask.useAppRuntimeFiles(packageTask.target) { (runtimeJars, mainJar) ->
            files.from(runtimeJars)
            launcherMainJar.set(mainJar)
        }
    }

    packageTask.launcherMainClass.set(provider { app.mainClass })
//    packageTask.launcherJvmArgs.set(provider { defaultJvmArgs + app.jvmArgs })
//    packageTask.launcherArgs.set(provider { app.args })
}

private fun JvmApplicationContext.configureAppImageTask(
    packageTask: AppImageTask,
    createRuntimeImage: TaskProvider<AbstractJLinkTask>? = null,
    prepareAppResources: TaskProvider<Sync>? = null,
    checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>? = null,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
    runProguard: Provider<AbstractProguardTask>? = null
) {
    packageTask.enabled = true

    createRuntimeImage?.let { createRuntimeImage ->
        packageTask.dependsOn(createRuntimeImage)
        packageTask.runtimeImage.set(createRuntimeImage.flatMap { it.destinationDir })
    }

    prepareAppResources?.let { prepareResources ->
        packageTask.dependsOn(prepareResources)
        val resourcesDir = packageTask.project.layout.dir(prepareResources.map { it.destinationDir })
        packageTask.appResourcesDir.set(resourcesDir)
    }

    checkRuntime?.let { checkRuntime ->
        packageTask.dependsOn(checkRuntime)
        packageTask.javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
        packageTask.jdkDir.set(checkRuntime.flatMap { it.jdk })
        packageTask.jdkVersion.set(checkRuntime.flatMap { it.targetJdkVersion })
    }

    configurePlatformSettings(packageTask, unpackDefaultResources)

    app.nativeDistributions.let { executables ->
        packageTask.packageName.set(packageNameProvider)
        packageTask.packageDescription.set(packageTask.provider { executables.description })
        packageTask.packageCopyright.set(packageTask.provider { executables.copyright })
        packageTask.packageVendor.set(packageTask.provider { executables.vendor })
        packageTask.packageVersion.set(packageVersionFor(packageTask.target.os))
        packageTask.licenseFile.set(executables.licenseFile)
    }

    packageTask.destinationDir.set(app.nativeDistributions.outputBaseDir.map {
        it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/appimage")
    })
    packageTask.javaHome.set(app.javaHomeProvider)

    if (runProguard != null) {
        packageTask.dependsOn(runProguard)
        packageTask.files.from(project.fileTree(runProguard.flatMap { it.destinationDir }))
        packageTask.launcherMainJar.set(runProguard.flatMap { it.mainJarInDestinationDir })
        packageTask.mangleJarFilesNames.set(false)
    } else {
        packageTask.useAppRuntimeFiles(packageTask.target) { (runtimeJars, mainJar) ->
            files.from(runtimeJars)
            launcherMainJar.set(mainJar)
        }
    }

    packageTask.launcherMainClass.set(provider { app.mainClass })
    packageTask.launcherJvmArgs.set(provider { defaultJvmArgs + app.jvmArgs })
    packageTask.launcherArgs.set(provider { app.args })
}

internal fun JvmApplicationContext.configureCommonNotarizationSettings(
    notarizationTask: AbstractNotarizationTask
) {
    notarizationTask.nonValidatedBundleID.set(app.nativeDistributions.macOS.bundleID)
    notarizationTask.nonValidatedNotarizationSettings = app.nativeDistributions.macOS.notarization
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: PackageDebTask,
    deb: DebianPlatformSettings,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.destinationDir.set(app.nativeDistributions.outputBaseDir.map {
        it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/deb")
    })
    packageTask.dependsOn(unpackDefaultResources)
    app.nativeDistributions.linux.also { linux ->
        packageTask.linuxShortcut.set(provider { linux.shortcut })
        packageTask.linuxAppCategory.set(provider { linux.appCategory })
        packageTask.linuxAppRelease.set(provider { linux.appRelease })
        packageTask.linuxDebPackageVersion.set(provider { linux.debPackageVersion })
        packageTask.linuxDebMaintainer.set(provider { linux.debMaintainer })
        packageTask.linuxMenuGroup.set(provider { linux.menuGroup })
        packageTask.linuxPackageName.set(provider { linux.packageName })
        packageTask.linuxRpmLicenseType.set(provider { linux.rpmLicenseType })
        packageTask.iconFile.set(linux.iconFile.orElse(unpackDefaultResources.flatMap { it.resources.linuxIcon }))
        packageTask.installationPath.set(linux.installationPath)
        packageTask.linuxDebPreInst.set(linux.debPreInst)
        packageTask.linuxDebPostInst.set(linux.debPostInst)
        packageTask.linuxDebPreRm.set(linux.debPreRm)
        packageTask.linuxDebPostRm.set(linux.debPostRm)
        packageTask.linuxDebCopyright.set(linux.debCopyright)
        packageTask.linuxDebLauncher.set(linux.debLauncher)
        packageTask.depends.set(deb.depends)
    }
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: PackageMsiTask,
    msi: MsiPlatformSettings,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.destinationDir.set(app.nativeDistributions.outputBaseDir.map {
        it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/msi")
    })
    packageTask.dependsOn(unpackDefaultResources)
    app.nativeDistributions.windows.also { win ->
        packageTask.winConsole.set(provider { win.console })
        packageTask.winDirChooser.set(provider { win.dirChooser })
        packageTask.winPerUserInstall.set(provider { win.perUserInstall })
        packageTask.winShortcut.set(provider { win.shortcut })
        packageTask.winMenu.set(provider { win.menu })
        packageTask.winMenuGroup.set(provider { win.menuGroup })
        packageTask.winUpgradeUuid.set(provider { win.upgradeUuid })
        packageTask.winPackageVersion.set(provider { win.packageVersion })
        packageTask.iconFile.set(win.iconFile.orElse(unpackDefaultResources.flatMap { it.resources.windowsIcon }))
        packageTask.installationPath.set(win.installationPath)
        packageTask.bitmapBanner.set(msi.bitmapBanner)
        packageTask.bitmapDialog.set(msi.bitmapDialog)
    }
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: AppImageTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.dependsOn(unpackDefaultResources)
    when (packageTask.target.os) {
        Linux -> {
            app.nativeDistributions.linux.also { linux ->
                packageTask.linuxShortcut.set(provider { linux.shortcut })
                packageTask.linuxAppCategory.set(provider { linux.appCategory })
                packageTask.linuxAppRelease.set(provider { linux.appRelease })
                packageTask.linuxDebMaintainer.set(provider { linux.debMaintainer })
                packageTask.linuxMenuGroup.set(provider { linux.menuGroup })
                packageTask.linuxPackageName.set(provider { linux.packageName })
                packageTask.linuxRpmLicenseType.set(provider { linux.rpmLicenseType })
                packageTask.iconFile.set(linux.iconFile.orElse(unpackDefaultResources.flatMap { it.resources.linuxIcon }))
                packageTask.installationPath.set(linux.installationPath)
            }
        }

        Windows -> {
            app.nativeDistributions.windows.also { win ->
                packageTask.winConsole.set(provider { win.console })
                packageTask.winDirChooser.set(provider { win.dirChooser })
                packageTask.winPerUserInstall.set(provider { win.perUserInstall })
                packageTask.winShortcut.set(provider { win.shortcut })
                packageTask.winMenu.set(provider { win.menu })
                packageTask.winMenuGroup.set(provider { win.menuGroup })
                packageTask.winUpgradeUuid.set(provider { win.upgradeUuid })
                packageTask.iconFile.set(win.iconFile.orElse(unpackDefaultResources.flatMap { it.resources.windowsIcon }))
                packageTask.installationPath.set(win.installationPath)
            }
        }

        OS.MacOS -> {
            app.nativeDistributions.macOS.also { mac ->
                packageTask.macPackageName.set(provider { mac.packageName })
                packageTask.macDockName.set(
                    if (mac.setDockNameSameAsPackageName)
                        provider { mac.dockName }
                            .orElse(packageTask.macPackageName).orElse(packageTask.packageName)
                    else
                        provider { mac.dockName }
                )
                packageTask.macAppStore.set(mac.appStore)
                packageTask.macAppCategory.set(mac.appCategory)
                packageTask.macEntitlementsFile.set(mac.entitlementsFile)
                packageTask.macRuntimeEntitlementsFile.set(mac.runtimeEntitlementsFile)
                packageTask.packageBuildVersion.set(packageVersionFor(currentOS))
                packageTask.nonValidatedMacBundleID.set(provider { mac.bundleID })
                packageTask.macProvisioningProfile.set(mac.provisioningProfile)
                packageTask.macRuntimeProvisioningProfile.set(mac.runtimeProvisioningProfile)
                packageTask.macExtraPlistKeysRawXml.set(provider { mac.infoPlistSettings.extraKeysRawXml })
                packageTask.nonValidatedMacSigningSettings = app.nativeDistributions.macOS.signing
                packageTask.iconFile.set(mac.iconFile.orElse(unpackDefaultResources.flatMap { it.resources.macIcon }))
                packageTask.installationPath.set(mac.installationPath)
            }
        }
    }
}

private fun JvmApplicationContext.configureRunTask(
    exec: JavaExec,
    prepareAppResources: TaskProvider<Sync>
) {
    exec.dependsOn(prepareAppResources)

    exec.mainClass.set(exec.provider { app.mainClass })
    exec.executable(javaExecutable(app.javaHome))
    exec.jvmArgs = arrayListOf<String>().apply {
        addAll(defaultJvmArgs)

        if (currentOS == OS.MacOS) {
            val file = app.nativeDistributions.macOS.iconFile.ioFileOrNull
            if (file != null) add("-Xdock:icon=$file")
        }

        addAll(app.jvmArgs)
        val appResourcesDir = prepareAppResources.get().destinationDir
        add("-D$APP_RESOURCES_DIR=${appResourcesDir.absolutePath}")
    }
    exec.args = app.args
    exec.useAppRuntimeFiles(currentTarget) { (runtimeJars, _) ->
        classpath = runtimeJars
    }
}

private fun JvmApplicationContext.configurePackageUberJarForCurrentOS(jar: Jar, os: OS) {
    fun flattenJars(files: FileCollection): FileCollection =
        jar.project.files({
            files.map { if (it.isZipOrJar()) jar.project.zipTree(it) else it }
        })

    jar.useAppRuntimeFiles(currentTarget) { (runtimeJars, _) ->
        from(flattenJars(runtimeJars))
    }

    app.mainClass?.let { jar.manifest.attributes["Main-Class"] = it }
    jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    jar.archiveAppendix.set(currentTarget.id)
    jar.archiveBaseName.set(packageNameProvider)
    jar.archiveVersion.set(packageVersionFor(os))
    jar.destinationDirectory.set(jar.project.layout.buildDirectory.dir("pinpit/jars"))

    jar.doLast {
        jar.logger.lifecycle("The jar is written to ${jar.archiveFile.ioFile.canonicalPath}")
    }
}

private fun File.isZipOrJar() =
    name.endsWith(".jar", ignoreCase = true)
            || name.endsWith(".zip", ignoreCase = true)
