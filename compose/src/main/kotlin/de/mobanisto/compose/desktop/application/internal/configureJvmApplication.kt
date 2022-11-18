/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.compose.desktop.application.internal

import de.mobanisto.compose.desktop.application.internal.OS.Linux
import de.mobanisto.compose.desktop.application.internal.OS.Windows
import de.mobanisto.compose.desktop.application.internal.validation.validatePackageVersions
import de.mobanisto.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import de.mobanisto.compose.desktop.application.tasks.AbstractJLinkTask
import de.mobanisto.compose.desktop.application.tasks.AbstractNotarizationTask
import de.mobanisto.compose.desktop.application.tasks.AbstractProguardTask
import de.mobanisto.compose.desktop.application.tasks.AbstractRunDistributableTask
import de.mobanisto.compose.desktop.application.tasks.AbstractSuggestModulesTask
import de.mobanisto.compose.desktop.application.tasks.AppImageTask
import de.mobanisto.compose.desktop.application.tasks.CustomDebTask
import de.mobanisto.compose.desktop.application.tasks.CustomMsiTask
import de.mobanisto.compose.desktop.application.tasks.CustomPackageTask
import de.mobanisto.compose.desktop.application.tasks.DownloadJdkTask
import de.mobanisto.compose.desktop.tasks.AbstractUnpackDefaultComposeApplicationResourcesTask
import de.mobanisto.compose.internal.uppercaseFirstChar
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
internal const val composeDesktopTaskGroup = "compose desktop"

// todo: multiple launchers
// todo: file associations
// todo: use workers
internal fun JvmApplicationContext.configureJvmApplication() {
    if (app.isDefaultConfigurationEnabled) {
        configureDefaultApp()
    }

    validatePackageVersions()
    val commonTasks = configureCommonJvmDesktopTasks()
    configurePackagingTasks(commonTasks)
    copy(buildType = app.buildTypes.release).configurePackagingTasks(commonTasks)
    configureWix()
}

internal class CommonJvmDesktopTasks(
    val unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
    val prepareAppResources: TaskProvider<Sync>,
)

internal class CommonJvmPackageTasks(
    val checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>,
    val createRuntimeImage: TaskProvider<AbstractJLinkTask>,
)

private fun JvmApplicationContext.configureCommonJvmDesktopTasks(): CommonJvmDesktopTasks {
    val unpackDefaultResources = tasks.register<AbstractUnpackDefaultComposeApplicationResourcesTask>(
        taskNameAction = "hokkaidoUnpack",
        taskNameObject = "DefaultComposeDesktopJvmApplicationResources"
    ) {}

    val prepareAppResources = tasks.register<Sync>(
        taskNameAction = "hokkaidoPrepare",
        taskNameObject = "appResources"
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

val downloadJdkTasks = mutableMapOf<String, TaskProvider<DownloadJdkTask>>()
val checkRuntimeTasks = mutableMapOf<String, TaskProvider<AbstractCheckNativeDistributionRuntime>>()
val suggestModulesTasks = mutableMapOf<String, TaskProvider<AbstractSuggestModulesTask>>()
val runtimeTasks = mutableMapOf<String, TaskProvider<AbstractJLinkTask>>()
val distributableTasks = mutableMapOf<String, TaskProvider<AppImageTask>>()
val runTasks = mutableMapOf<String, TaskProvider<AbstractRunDistributableTask>>()

private fun JvmApplicationContext.configurePackagingTasks(
    commonTasks: CommonJvmDesktopTasks
) {
    val runProguard = if (buildType.proguard.isEnabled.orNull == true) {
        tasks.register<AbstractProguardTask>(
            taskNameAction = "hokkaidoProguard",
            taskNameObject = "Jars"
        ) {
            configureProguardTask(this, commonTasks.unpackDefaultResources)
        }
    } else null

    val jdkInfo = jdkInfo(app.nativeDistributions.jvmVendor!!, app.nativeDistributions.jvmVersion!!)
        ?: throw GradleException("Invalid JVM vendor or version")

    app.nativeDistributions.windows.msis.forEach { msi ->
        val arch = msi.arch!!
        val os = "windows"

        val osArchKey = "$os:$arch"
        val osArch = os.uppercaseFirstChar() + arch.uppercaseFirstChar()

        val packageTasks = configureCommonPackageTasks(tasks, jdkInfo, os, arch, app, appTmpDir)

        val createDistributable = distributableTasks[osArchKey] ?: tasks.register<AppImageTask>(
            taskNameAction = "hokkaido",
            taskNameObject = "distributable$osArch",
        ) {
            configureAppImageTask(
                this,
                os = Windows,
                arch = arch,
                createRuntimeImage = packageTasks.createRuntimeImage,
                prepareAppResources = commonTasks.prepareAppResources,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources,
                runProguard = runProguard
            )
        }.also { distributableTasks[osArchKey] = it }

        val runDistributable = runTasks[osArchKey] ?: tasks.register<AbstractRunDistributableTask>(
            taskNameAction = "hokkaidoRun",
            taskNameObject = "distributable$osArch",
            args = listOf(createDistributable)
        ).also { runTasks[osArchKey] = it }

        tasks.register<CustomMsiTask>(
            taskNameAction = "hokkaido",
            taskNameObject = "msi" + arch.uppercaseFirstChar(),
            args = listOf(arch)
        ) {
            configureCustomPackageTask(
                this,
                createAppImage = createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }
    }

    app.nativeDistributions.linux.debs.forEach { deb ->
        val arch = deb.arch!!
        val distro = deb.distro!!
        val os = "linux"

        val osArchKey = "$os:$arch"
        val osArch = os.uppercaseFirstChar() + arch.uppercaseFirstChar()

        val packageTasks = configureCommonPackageTasks(tasks, jdkInfo, os, arch, app, appTmpDir)

        val createDistributable = distributableTasks[osArchKey] ?: tasks.register<AppImageTask>(
            taskNameAction = "hokkaido",
            taskNameObject = "distributable$osArch",
        ) {
            configureAppImageTask(
                this,
                os = Linux,
                arch = arch,
                createRuntimeImage = packageTasks.createRuntimeImage,
                prepareAppResources = commonTasks.prepareAppResources,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources,
                runProguard = runProguard
            )
        }.also { distributableTasks[osArchKey] = it }

        val runDistributable = runTasks[osArchKey] ?: tasks.register<AbstractRunDistributableTask>(
            taskNameAction = "hokkaidoRun",
            taskNameObject = "distributable$osArch",
            args = listOf(createDistributable)
        ).also { runTasks[osArchKey] = it }

        tasks.register<CustomDebTask>(
            taskNameAction = "hokkaido",
            taskNameObject = "deb" + distro.uppercaseFirstChar(),
            args = listOf(arch, deb.qualifier!!)
        ) {
            configureCustomPackageTask(
                this,
                createAppImage = createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }
    }

    if (buildType === app.buildTypes.default) {
        tasks.register<DefaultTask>("hokkaidoPackage") {
            // TODO: depend on all package tasks
            // dependsOn(packageForCurrentOS)
        }
    }

    val packageUberJarForCurrentOS = tasks.register<Jar>(
        taskNameAction = "hokkaidoPackage",
        taskNameObject = "uberJarForCurrentOS"
    ) {
        configurePackageUberJarForCurrentOS(this, currentOS)
    }

    val run = tasks.register<JavaExec>(taskNameAction = "hokkaidoRun") {
        configureRunTask(this, commonTasks.prepareAppResources)
    }
}

private fun JvmApplicationContext.configureCommonPackageTasks(
    tasks: JvmTasks,
    jdkInfo: JdkInfo,
    os: String,
    arch: String,
    app: JvmApplicationData,
    appTmpDir: Provider<Directory>
): CommonJvmPackageTasks {
    val osArchKey = "$os:$arch"
    val osArch = os.uppercaseFirstChar() + arch.uppercaseFirstChar()

    val downloadJdk = downloadJdkTasks[osArchKey] ?: tasks.register<DownloadJdkTask>(
        taskNameAction = "hokkaido",
        taskNameObject = "download$osArch",
    ) {
        jvmVendor.set(app.nativeDistributions.jvmVendor)
        jvmVersion.set(app.nativeDistributions.jvmVersion)
        this.os.set(os)
        this.arch.set(arch)
    }.also { downloadJdkTasks[osArchKey] = it }

    val checkRuntime = checkRuntimeTasks[osArchKey] ?: tasks.register<AbstractCheckNativeDistributionRuntime>(
        taskNameAction = "hokkaidoCheck",
        taskNameObject = "runtime$osArch"
    ) {
        dependsOn(downloadJdk)
        targetJdkVersion.set(jdkInfo.major)
        javaHome.set(app.javaHomeProvider)
        jdk.set(provider { downloadJdk.get().jdkDir })
    }.also { checkRuntimeTasks[osArchKey] = it }

    val suggestRuntimeModules = suggestModulesTasks[osArchKey] ?: tasks.register<AbstractSuggestModulesTask>(
        taskNameAction = "hokkaidoSuggest",
        taskNameObject = "runtimeModules$osArch"
    ) {
        dependsOn(checkRuntime)
        jdk.set(provider { downloadJdk.get().jdkDir })
        modules.set(provider { app.nativeDistributions.modules })

        useAppRuntimeFiles { (jarFiles, mainJar) ->
            files.from(jarFiles)
            launcherMainJar.set(mainJar)
        }
    }.also { suggestModulesTasks[osArchKey] = it }

    val createRuntimeImage = runtimeTasks[osArchKey] ?: tasks.register<AbstractJLinkTask>(
        taskNameAction = "hokkaido",
        taskNameObject = "runtimeImage$osArch"
    ) {
        dependsOn(checkRuntime)
        dependsOn(downloadJdk)
        jdk.set(provider { downloadJdk.get().jdkDir })
        javaHome.set(app.javaHomeProvider)
        modules.set(provider { app.nativeDistributions.modules })
        includeAllModules.set(provider { app.nativeDistributions.includeAllModules })
        javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
        destinationDir.set(appTmpDir.dir("$os/$arch/runtime"))
    }.also { runtimeTasks[osArchKey] = it }

    return CommonJvmPackageTasks(checkRuntime, createRuntimeImage)
}

private fun JvmApplicationContext.configureProguardTask(
    proguard: AbstractProguardTask,
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

    useAppRuntimeFiles { files ->
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

    configurePlatformSettings(packageTask, unpackDefaultResources)

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

    packageTask.destinationDir.set(app.nativeDistributions.outputBaseDir.map {
        it.dir("$appDirName/${packageTask.targetFormat.outputDirName}")
    })
//    packageTask.javaHome.set(app.javaHomeProvider)

    if (runProguard != null) {
        packageTask.dependsOn(runProguard)
        packageTask.files.from(project.fileTree(runProguard.flatMap { it.destinationDir }))
        packageTask.launcherMainJar.set(runProguard.flatMap { it.mainJarInDestinationDir })
        packageTask.mangleJarFilesNames.set(false)
    } else {
        packageTask.useAppRuntimeFiles { (runtimeJars, mainJar) ->
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
    os: OS,
    arch: String,
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
    }

    this.configurePlatformSettings(packageTask, unpackDefaultResources)

    app.nativeDistributions.let { executables ->
        packageTask.packageName.set(packageNameProvider)
        packageTask.packageDescription.set(packageTask.provider { executables.description })
        packageTask.packageCopyright.set(packageTask.provider { executables.copyright })
        packageTask.packageVendor.set(packageTask.provider { executables.vendor })
        packageTask.packageVersion.set(packageVersionFor(os))
        packageTask.licenseFile.set(executables.licenseFile)
    }

    packageTask.destinationDir.set(app.nativeDistributions.outputBaseDir.map {
        it.dir("$appDirName/${os.id}/$arch/appimage")
    })
    packageTask.javaHome.set(app.javaHomeProvider)

    if (runProguard != null) {
        packageTask.dependsOn(runProguard)
        packageTask.files.from(project.fileTree(runProguard.flatMap { it.destinationDir }))
        packageTask.launcherMainJar.set(runProguard.flatMap { it.mainJarInDestinationDir })
        packageTask.mangleJarFilesNames.set(false)
    } else {
        packageTask.useAppRuntimeFiles { (runtimeJars, mainJar) ->
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
    packageTask: CustomPackageTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    if (packageTask is CustomDebTask) {
        configurePlatformSettings(packageTask, unpackDefaultResources)
    } else if (packageTask is CustomMsiTask) {
        configurePlatformSettings(packageTask, unpackDefaultResources)
    }
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: CustomDebTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.dependsOn(unpackDefaultResources)
    if (currentOS == Linux) {
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
            packageTask.linuxDebAdditionalDependencies.set(provider { linux.debAdditionalDependencies })
            packageTask.linuxDebCopyright.set(linux.debCopyright)
            packageTask.linuxDebLauncher.set(linux.debLauncher)
        }
    }
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: CustomMsiTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.dependsOn(unpackDefaultResources)
    if (currentOS == Windows) {
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
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: AppImageTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.dependsOn(unpackDefaultResources)
    when (currentOS) {
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
    exec.useAppRuntimeFiles { (runtimeJars, _) ->
        classpath = runtimeJars
    }
}

private fun JvmApplicationContext.configurePackageUberJarForCurrentOS(jar: Jar, os: OS) {
    fun flattenJars(files: FileCollection): FileCollection =
        jar.project.files({
            files.map { if (it.isZipOrJar()) jar.project.zipTree(it) else it }
        })


    jar.useAppRuntimeFiles { (runtimeJars, _) ->
        from(flattenJars(runtimeJars))
    }

    app.mainClass?.let { jar.manifest.attributes["Main-Class"] = it }
    jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    jar.archiveAppendix.set(currentTarget.id)
    jar.archiveBaseName.set(packageNameProvider)
    jar.archiveVersion.set(packageVersionFor(os))
    jar.destinationDirectory.set(jar.project.layout.buildDirectory.dir("hokkaido/jars"))

    jar.doLast {
        jar.logger.lifecycle("The jar is written to ${jar.archiveFile.ioFile.canonicalPath}")
    }
}

private fun File.isZipOrJar() =
    name.endsWith(".jar", ignoreCase = true)
            || name.endsWith(".zip", ignoreCase = true)
