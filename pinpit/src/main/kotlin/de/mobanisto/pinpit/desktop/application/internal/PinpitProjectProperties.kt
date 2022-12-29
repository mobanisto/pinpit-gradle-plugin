/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package de.mobanisto.pinpit.desktop.application.internal

import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

internal object PinpitProperties {
    internal const val VERBOSE = "pinpit.desktop.verbose"
    internal const val OVERRIDE_KOTLIN_JVM_TARGET = "pinpit.desktop.override.default.kotlin.jvm.target"
    internal const val PRESERVE_WD = "pinpit.preserve.working.dir"
    internal const val MAC_SIGN = "pinpit.desktop.mac.sign"
    internal const val MAC_SIGN_ID = "pinpit.desktop.mac.signing.identity"
    internal const val MAC_SIGN_KEYCHAIN = "pinpit.desktop.mac.signing.keychain"
    internal const val MAC_SIGN_PREFIX = "pinpit.desktop.mac.signing.prefix"
    internal const val MAC_NOTARIZATION_APPLE_ID = "pinpit.desktop.mac.notarization.appleID"
    internal const val MAC_NOTARIZATION_PASSWORD = "pinpit.desktop.mac.notarization.password"
    internal const val MAC_NOTARIZATION_ASC_PROVIDER = "pinpit.desktop.mac.notarization.ascProvider"

    fun isVerbose(providers: ProviderFactory): Provider<Boolean> =
        providers.findProperty(VERBOSE).toBoolean()

    fun overrideKotlinJvmTarget(providers: ProviderFactory): Provider<Boolean> =
        providers.provider {
            providers.findProperty(OVERRIDE_KOTLIN_JVM_TARGET)?.toString() != "false"
        }

    fun preserveWorkingDir(providers: ProviderFactory): Provider<Boolean> =
        providers.findProperty(PRESERVE_WD).toBoolean()

    fun macSign(providers: ProviderFactory): Provider<Boolean> =
        providers.findProperty(MAC_SIGN).toBoolean()

    fun macSignIdentity(providers: ProviderFactory): Provider<String?> =
        providers.findProperty(MAC_SIGN_ID)

    fun macSignKeychain(providers: ProviderFactory): Provider<String?> =
        providers.findProperty(MAC_SIGN_KEYCHAIN)

    fun macSignPrefix(providers: ProviderFactory): Provider<String?> =
        providers.findProperty(MAC_SIGN_PREFIX)

    fun macNotarizationAppleID(providers: ProviderFactory): Provider<String?> =
        providers.findProperty(MAC_NOTARIZATION_APPLE_ID)

    fun macNotarizationPassword(providers: ProviderFactory): Provider<String?> =
        providers.findProperty(MAC_NOTARIZATION_PASSWORD)

    fun macNotarizationAscProvider(providers: ProviderFactory): Provider<String?> =
        providers.findProperty(MAC_NOTARIZATION_ASC_PROVIDER)

    private fun ProviderFactory.findProperty(prop: String): Provider<String?> =
        provider {
            gradleProperty(prop).forUseAtConfigurationTimeSafe().orNull
        }

    private fun Provider<String?>.forUseAtConfigurationTimeSafe(): Provider<String?> =
        try {
            forUseAtConfigurationTime()
        } catch (e: NoSuchMethodError) {
            // todo: remove once we drop support for Gradle 6.4
            this
        }

    private fun Provider<String?>.toBoolean(): Provider<Boolean> =
        orElse("false").map { "true" == it }
}
