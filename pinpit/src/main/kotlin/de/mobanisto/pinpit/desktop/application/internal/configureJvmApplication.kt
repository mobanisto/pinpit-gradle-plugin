/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import de.mobanisto.pinpit.desktop.application.dsl.ArchiveFormat
import de.mobanisto.pinpit.desktop.application.dsl.DebianPlatformSettings
import de.mobanisto.pinpit.desktop.application.dsl.DistributableArchiveSettings
import de.mobanisto.pinpit.desktop.application.dsl.JvmApplicationBuildType
import de.mobanisto.pinpit.desktop.application.dsl.MsiPlatformSettings
import de.mobanisto.pinpit.desktop.application.dsl.TargetFormat.DistributableArchive
import de.mobanisto.pinpit.desktop.application.internal.OS.Linux
import de.mobanisto.pinpit.desktop.application.internal.OS.Windows
import de.mobanisto.pinpit.desktop.application.internal.validation.validatePackageVersions
import de.mobanisto.pinpit.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import de.mobanisto.pinpit.desktop.application.tasks.AbstractJLinkTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractNotarizationTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractProguardTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractRunDistributableTask
import de.mobanisto.pinpit.desktop.application.tasks.AbstractSuggestModulesTask
import de.mobanisto.pinpit.desktop.application.tasks.CustomPackageTask
import de.mobanisto.pinpit.desktop.application.tasks.DistributableAppTask
import de.mobanisto.pinpit.desktop.application.tasks.DownloadJdkTask
import de.mobanisto.pinpit.desktop.application.tasks.linux.PackageDebTask
import de.mobanisto.pinpit.desktop.application.tasks.linux.PackageLinuxDistributableArchiveTask
import de.mobanisto.pinpit.desktop.application.tasks.linux.SuggestDebDependenciesTask
import de.mobanisto.pinpit.desktop.application.tasks.windows.PackageMsiTask
import de.mobanisto.pinpit.desktop.application.tasks.windows.PackageWindowsDistributableArchiveTask
import de.mobanisto.pinpit.desktop.application.tasks.windows.configurePeRebrander
import de.mobanisto.pinpit.desktop.application.tasks.windows.configureWix
import de.mobanisto.pinpit.desktop.tasks.AbstractUnpackDefaultComposeApplicationResourcesTask
import de.mobanisto.pinpit.internal.addUnique
import de.mobanisto.pinpit.internal.uppercaseFirstChar
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
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
    configurePackagingTasks(targets, targetTasks, commonTasks)
    // TODO: re-enable release build type and its tasks. Make sure they actually work though.
    // copy(buildType = app.buildTypes.release).configurePackagingTasks(targets, targetTasks, commonTasks)
    configureWix()
    configurePeRebrander()
}

internal data class TargetAndBuildType(val target: Target, val buildType: JvmApplicationBuildType)

internal class TargetTasks {
    val downloadJdkTasks = mutableMapOf<Target, TaskProvider<DownloadJdkTask>>()
    val checkRuntimeTasks = mutableMapOf<Target, TaskProvider<AbstractCheckNativeDistributionRuntime>>()
    val suggestModulesTasks = mutableMapOf<Target, TaskProvider<AbstractSuggestModulesTask>>()
    val prepareAppResources = mutableMapOf<Target, TaskProvider<Sync>>()
    val proguardTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AbstractProguardTask>>()
    val runtimeTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AbstractJLinkTask>>()
    val distributableTasks = mutableMapOf<TargetAndBuildType, TaskProvider<DistributableAppTask>>()
    val runTasks = mutableMapOf<TargetAndBuildType, TaskProvider<AbstractRunDistributableTask>>()
    val suggestDebDependenciesTasks = mutableMapOf<TargetAndBuildType, TaskProvider<SuggestDebDependenciesTask>>()
}

internal class CommonJvmDesktopTasks(
    val unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
)

internal class CommonJvmPackageTasks(
    val checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>,
    val runProguard: TaskProvider<AbstractProguardTask>?,
    val createRuntimeImage: TaskProvider<AbstractJLinkTask>,
    val createDistributable: TaskProvider<DistributableAppTask>,
)

private fun JvmApplicationContext.configureCommonJvmDesktopTasks(): CommonJvmDesktopTasks {
    val unpackDefaultResources = tasks.register<AbstractUnpackDefaultComposeApplicationResourcesTask>(
        taskNameAction = "pinpitUnpack",
        taskNameObject = "DefaultComposeDesktopJvmApplicationResources",
        useBuildTypeForTaskName = false,
        description = "Unpacks the default Compose resources such as launcher icons.",
    ) {}

    return CommonJvmDesktopTasks(
        unpackDefaultResources,
    )
}

private fun JvmApplicationContext.configurePackagingTasks(
    targets: List<Target>,
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

    val allPackageTasks = mutableListOf<TaskProvider<out Task>>()
    val allUberJarTasks = mutableListOf<TaskProvider<out Task>>()

    targets.forEach { target ->
        if (buildType == app.buildTypes.default) {
            tasks.register<Jar>(
                taskNameAction = "pinpitPackage",
                taskNameObject = "uberJarFor${target.name}",
                description = "Packages an Uber-Jar for ${target.name}.",
            ) {
                configurePackageUberJar(this, target)
            }.also { allUberJarTasks.add(it) }
        }
    }

    app.nativeDistributions.windows.distributableArchives.forEach { archive ->
        val target = Target(Windows, arch(archive.arch))
        val targetBuild = TargetAndBuildType(target, buildType)

        val packageTasks = configureCommonPackageTasks(
            tasks, jdkInfo, targetBuild, app, appTmpDir, targetTasks, commonTasks
        )

        val format = determineArchiveFormat(archive)
        val targetFormat = DistributableArchive(target.os, format)

        tasks.register<PackageWindowsDistributableArchiveTask>(
            taskNameAction = "pinpitPackage",
            taskNameObject = "distributable${format.name}${target.name}",
            description = "Builds a distributable ${format.name} archive for ${target.name}.",
            args = listOf(target, targetFormat),
        ) {
            configureCustomPackageTask(
                this,
                createDistributableApp = packageTasks.createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
            configurePlatformSettings(
                this, unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }.also { allPackageTasks.add(it) }
    }

    app.nativeDistributions.windows.msis.forEach { msi ->
        val target = Target(Windows, arch(msi.arch))
        val targetBuild = TargetAndBuildType(target, buildType)

        val packageTasks = configureCommonPackageTasks(
            tasks, jdkInfo, targetBuild, app, appTmpDir, targetTasks, commonTasks
        )

        tasks.register<PackageMsiTask>(
            taskNameAction = "pinpitPackage",
            taskNameObject = "msi" + target.arch.id.uppercaseFirstChar(),
            description = "Builds an MSI package for ${target.name}.",
            args = listOf(target),
        ) {
            configureCustomPackageTask(
                this,
                createDistributableApp = packageTasks.createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
            configurePlatformSettings(
                this, msi = msi, unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }.also { allPackageTasks.add(it) }
    }

    app.nativeDistributions.linux.distributableArchives.forEach { archive ->
        val target = Target(Linux, arch(archive.arch))
        val targetBuild = TargetAndBuildType(target, buildType)

        val packageTasks = configureCommonPackageTasks(
            tasks, jdkInfo, targetBuild, app, appTmpDir, targetTasks, commonTasks
        )

        val format = determineArchiveFormat(archive)
        val targetFormat = DistributableArchive(target.os, format)

        tasks.register<PackageLinuxDistributableArchiveTask>(
            taskNameAction = "pinpitPackage",
            taskNameObject = "distributable${format.name}${target.name}",
            description = "Builds a distributable ${format.name} archive for ${target.name}.",
            args = listOf(target, targetFormat),
        ) {
            configureCustomPackageTask(
                this,
                createDistributableApp = packageTasks.createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
            configurePlatformSettings(
                this, unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }.also { allPackageTasks.add(it) }
    }

    app.nativeDistributions.linux.debs.forEach { deb ->
        val target = Target(Linux, arch(deb.arch))
        val targetBuild = TargetAndBuildType(target, buildType)
        val distro = deb.distro!!

        val packageTasks = configureCommonPackageTasks(
            tasks, jdkInfo, targetBuild, app, appTmpDir, targetTasks, commonTasks
        )

        tasks.register<PackageDebTask>(
            taskNameAction = "pinpitPackage",
            taskNameObject = "deb" + distro.uppercaseFirstChar(),
            description = "Builds a DEB package for ${target.name}.",
            args = listOf(target, deb.qualifier!!),
        ) {
            configureCustomPackageTask(
                this,
                createDistributableApp = packageTasks.createDistributable,
                checkRuntime = packageTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources
            )
            configurePlatformSettings(
                this, deb = deb, unpackDefaultResources = commonTasks.unpackDefaultResources
            )
        }.also { allPackageTasks.add(it) }
    }

    tasks.register<DefaultTask>(
        taskNameAction = "pinpitCreate",
        taskNameObject = "runtime",
        description = "Creates a runtime image for each system and architecture using jlink.",
    ) {
        targetTasks.runtimeTasks.values.forEach {
            dependsOn(it)
        }
    }

    tasks.register<DefaultTask>(
        taskNameAction = "pinpitCreate",
        taskNameObject = "distributable",
        description = "Creates a directory for each system and architecture containing all files to be distributed including launcher, app and runtime image.",
    ) {
        targetTasks.distributableTasks.values.forEach {
            dependsOn(it)
        }
    }

    tasks.register<DefaultTask>(
        taskNameAction = "pinpitPackage",
        description = "Builds packages for all systems and architectures.",
    ) {
        allPackageTasks.forEach {
            dependsOn(it)
        }
        allUberJarTasks.forEach {
            dependsOn(it)
        }
    }

    tasks.register<DefaultTask>(
        taskNameAction = "pinpitPackage",
        taskNameObject = "uberJar",
        description = "Packages an Uber-Jar for each system and architecture.",
    ) {
        allUberJarTasks.forEach {
            dependsOn(it)
        }
    }

    if (buildType == app.buildTypes.default) {
        val run = tasks.register<JavaExec>(
            taskNameAction = "pinpitRun",
            description = "Runs the application.",
            useBuildTypeForTaskName = false,
        ) {
            val prepareAppResources = checkNotNull(targetTasks.prepareAppResources[currentTarget])
            configureRunTask(this, prepareAppResources)
        }
    }

    if (currentOS == Linux) {
        val defaultTargetBuild = TargetAndBuildType(currentTarget, app.buildTypes.default)
        val createDistributable = targetTasks.distributableTasks[defaultTargetBuild]
        val suggestDebDependencies = targetTasks.suggestDebDependenciesTasks[defaultTargetBuild]
        if (createDistributable != null && suggestDebDependencies == null) {
            tasks.register<SuggestDebDependenciesTask>(
                taskNameAction = "pinpitSuggestDebDependencies",
                description = "Suggests Debian package dependencies to use for the current OS using dpkg.",
                useBuildTypeForTaskName = false,
            ) {
                dependsOn(createDistributable)
                distributableApp.set(createDistributable.flatMap { it.destinationDir })
            }.also { targetTasks.suggestDebDependenciesTasks[defaultTargetBuild] = it }
        }
    }
}

fun determineArchiveFormat(archive: DistributableArchiveSettings): ArchiveFormat {
    ArchiveFormat.values().forEach { format ->
        if (archive.format == format.extension) {
            return format
        }
    }

    throw GradleException("Invalid archive format ${archive.format}")
}

private fun JvmApplicationContext.configureCommonPackageTasks(
    tasks: JvmTasks,
    jdkInfo: JdkInfo,
    targetBuild: TargetAndBuildType,
    app: JvmApplicationData,
    appTmpDir: Provider<Directory>,
    targetTasks: TargetTasks,
    commonTasks: CommonJvmDesktopTasks,
): CommonJvmPackageTasks {
    val target = targetBuild.target

    val downloadJdk = targetTasks.downloadJdkTasks[target] ?: tasks.register<DownloadJdkTask>(
        taskNameAction = "pinpitDownload",
        taskNameObject = "jdk${target.name}",
        description = "Downloads the JDK for ${target.name} that is used to derive a runtime to distribute with the app.",
        useBuildTypeForTaskName = false,
    ) {
        jvmVendor.set(app.nativeDistributions.jvmVendor)
        jvmVersion.set(app.nativeDistributions.jvmVersion)
        this.os.set(target.os.id)
        this.arch.set(target.arch.id)
    }.also { targetTasks.downloadJdkTasks[target] = it }

    val checkRuntime = targetTasks.checkRuntimeTasks[target] ?: tasks.register<AbstractCheckNativeDistributionRuntime>(
        taskNameAction = "pinpitCheck",
        taskNameObject = "runtime${target.name}",
        description = "Checks that the JDK used for building is compatible with the distribution JVM.",
        useBuildTypeForTaskName = false,
    ) {
        dependsOn(downloadJdk)
        targetJdkVersion.set(jdkInfo.major)
        javaHome.set(app.javaHomeProvider)
        jdk.set(provider { downloadJdk.get().jdkDir })
    }.also { targetTasks.checkRuntimeTasks[target] = it }

    val suggestRuntimeModules = targetTasks.suggestModulesTasks[target] ?: tasks.register<AbstractSuggestModulesTask>(
        taskNameAction = "pinpitSuggest",
        taskNameObject = "runtimeModules${target.name}",
        description = "Suggests JVM modules to include for the distribution using jdeps.",
        useBuildTypeForTaskName = false,
    ) {
        dependsOn(checkRuntime)
        jdk.set(provider { downloadJdk.get().jdkDir })
        modules.set(provider { app.nativeDistributions.modules })

        useAppRuntimeFiles(target) { (jarFiles, mainJar) ->
            files.from(jarFiles)
            launcherMainJar.set(mainJar)
        }
    }.also { targetTasks.suggestModulesTasks[target] = it }

    val prepareAppResources = targetTasks.prepareAppResources[target] ?: tasks.register<Sync>(
        taskNameAction = "pinpitPrepare",
        taskNameObject = "appResources${target.name}",
        useBuildTypeForTaskName = false,
        description = "Merge all app resources for ${target.name} into a single build directory.",
    ) {
        val appResourcesRootDir = app.nativeDistributions.appResourcesRootDir
        if (appResourcesRootDir.isPresent) {
            from(appResourcesRootDir.dir("common"))
            from(appResourcesRootDir.dir(target.os.id))
            from(appResourcesRootDir.dir(target.id))
        }
        into(jvmTmpDirForTask())
    }.also { targetTasks.prepareAppResources[target] = it }

    val runProguard = targetTasks.proguardTasks[targetBuild] ?: if (buildType.proguard.isEnabled.orNull == true) {
        tasks.register<AbstractProguardTask>(
            taskNameAction = "pinpitProguard",
            taskNameObject = "jars${target.name}",
            description = "Runs Proguard to minify and obfuscate release jars.",
        ) {
            configureProguardTask(this, target, /*targetData,*/ commonTasks.unpackDefaultResources)
        }.also { targetTasks.proguardTasks[targetBuild] = it }
    } else null

    val createRuntimeImage = targetTasks.runtimeTasks[targetBuild] ?: tasks.register<AbstractJLinkTask>(
        taskNameAction = "pinpitCreate",
        taskNameObject = "runtimeImage${target.name}",
        description = "Creates a runtime image from the JVM for ${target.name} using jlink."
    ) {
        dependsOn(checkRuntime)
        dependsOn(downloadJdk)
        jdk.set(provider { downloadJdk.get().jdkDir })
        javaHome.set(app.javaHomeProvider)
        modules.set(provider { app.nativeDistributions.modules })
        includeAllModules.set(provider { app.nativeDistributions.includeAllModules })
        javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
        destinationDir.set(appTmpDir.dir("${target.os.id}/${target.arch.id}/runtime"))
    }.also { targetTasks.runtimeTasks[targetBuild] = it }

    val createDistributable = targetTasks.distributableTasks[targetBuild] ?: tasks.register<DistributableAppTask>(
        taskNameAction = "pinpitCreate",
        taskNameObject = "distributable${target.name}",
        description = "Creates a directory for ${target.name} containing all files to be distributed including launcher, app and runtime image.",
        args = listOf(target),
    ) {
        configureDistributableAppTask(
            this,
            createRuntimeImage = createRuntimeImage,
            prepareAppResources = targetTasks.prepareAppResources[target],
            checkRuntime = checkRuntime,
            unpackDefaultResources = commonTasks.unpackDefaultResources,
            runProguard = runProguard
        )
    }.also { targetTasks.distributableTasks[targetBuild] = it }

    val runDistributable = targetTasks.runTasks[targetBuild] ?: tasks.register<AbstractRunDistributableTask>(
        taskNameAction = "pinpitRun",
        taskNameObject = "distributable${target.name}",
        description = "Runs the app from the created distributable directory for ${target.name}.",
        args = listOf(createDistributable),
    ).also { targetTasks.runTasks[targetBuild] = it }

    return CommonJvmPackageTasks(checkRuntime, runProguard, createRuntimeImage, createDistributable)
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
    createDistributableApp: TaskProvider<DistributableAppTask>,
    checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>,
    runProguard: Provider<AbstractProguardTask>? = null
) {
    createDistributableApp.let { createDistributableApp ->
        packageTask.dependsOn(createDistributableApp)
        packageTask.distributableApp.set(createDistributableApp.flatMap { it.destinationDir })
    }

    checkRuntime.let { checkRuntime ->
        packageTask.dependsOn(checkRuntime)
        packageTask.javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
    }

    packageTask.dependsOn(unpackDefaultResources)

    app.nativeDistributions.let { executables ->
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
}

private fun JvmApplicationContext.configureDistributableAppTask(
    packageTask: DistributableAppTask,
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
    }

    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/distributableApp")
        }
    )
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
    packageTask: PackageLinuxDistributableArchiveTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/distributableArchive")
        }
    )
    packageTask.dependsOn(unpackDefaultResources)
    app.nativeDistributions.linux.also { linux ->
        packageTask.linuxPackageName.set(provider { linux.packageName })
        packageTask.packageVersion.set(provider { linux.packageVersion ?: app.nativeDistributions.packageVersion })
    }
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: PackageWindowsDistributableArchiveTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/distributableArchive")
        }
    )
    packageTask.dependsOn(unpackDefaultResources)
    app.nativeDistributions.windows.also { windows ->
        packageTask.packageVersion.set(provider { windows.packageVersion ?: app.nativeDistributions.packageVersion })
    }
}

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: PackageDebTask,
    deb: DebianPlatformSettings,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/deb")
        }
    )
    packageTask.dependsOn(unpackDefaultResources)
    app.nativeDistributions.linux.also { linux ->
        packageTask.linuxShortcut.set(provider { linux.shortcut })
        packageTask.linuxAppCategory.set(provider { linux.appCategory })
        packageTask.linuxAppRelease.set(provider { linux.appRelease })
        packageTask.linuxDebPackageVersion.set(provider { linux.debPackageVersion })
        packageTask.linuxDebMaintainer.set(provider { linux.debMaintainer })
        packageTask.linuxMenuGroup.set(provider { linux.menuGroup })
        packageTask.linuxPackageName.set(provider { linux.packageName })
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
    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.target.os.id}/${packageTask.target.arch.id}/msi")
        }
    )
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
    packageTask: DistributableAppTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultComposeApplicationResourcesTask>
) {
    packageTask.dependsOn(unpackDefaultResources)
    when (packageTask.target.os) {
        Linux -> {
            app.nativeDistributions.linux.also { linux ->
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
                    if (mac.setDockNameSameAsPackageName) provider { mac.dockName }.orElse(
                        packageTask.macPackageName
                    ).orElse(packageTask.packageName)
                    else provider { mac.dockName }
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

private fun JvmApplicationContext.configurePackageUberJar(jar: Jar, target: Target) {
    fun flattenJars(files: FileCollection): FileCollection = jar.project.files({
        files.map { if (it.isZipOrJar()) jar.project.zipTree(it) else it }
    })

    jar.useAppRuntimeFiles(target) { (runtimeJars, _) ->
        from(flattenJars(runtimeJars))
    }

    app.mainClass?.let { jar.manifest.attributes["Main-Class"] = it }
    jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    jar.archiveAppendix.set(target.id)
    jar.archiveBaseName.set(packageNameProvider)
    jar.archiveVersion.set(packageVersionFor(target.os))
    jar.destinationDirectory.set(jar.project.layout.buildDirectory.dir("pinpit/jars"))

    jar.doLast {
        jar.logger.lifecycle("The jar is written to ${jar.archiveFile.ioFile.canonicalPath}")
    }
}

private fun File.isZipOrJar() = name.endsWith(".jar", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true)
