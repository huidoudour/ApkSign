@file:Suppress("DEPRECATION")

import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
}

// ── Git 版本控制 ──
val appBackVersion = 3
val appBaseVersion = "26.1"

fun Project.gitCommitCount(): Int = try {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (_: Exception) { appBackVersion }

fun Project.gitHash(): String = try {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }
        .standardOutput.asText.get().trim()
} catch (_: Exception) {
    SimpleDateFormat("MMddHHmm").format(Date())
}

val appVersionCode = gitCommitCount()
val appVersionName = "${appBaseVersion}.${gitCommitCount()}.${gitHash()}"

tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
    doLast {
        println(">>>[$name]:BuildSuccessful | versionName=$appVersionName | versionCode=$appVersionCode<<<")
    }
}

android {
    namespace = "me.huidoudour.apksign"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "me.huidoudour.apksign"
        minSdk = 29
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val useSignKey = rootProject.hasProperty("storeFile") &&
        rootProject.hasProperty("storePassword") &&
        rootProject.hasProperty("keyAlias") &&
        rootProject.hasProperty("keyPassword")
    val devSignKey = rootProject.hasProperty("dbgFilePath") &&
        rootProject.hasProperty("dbgPassword") &&
        rootProject.hasProperty("dbgKeyAlias") &&
        rootProject.hasProperty("dbgKeyPaswd")

    if (useSignKey) {
        signingConfigs {
            register("sign_key") {
                storeFile = file(rootProject.property("storeFile") as String)
                storePassword = rootProject.property("storePassword") as String
                keyAlias = rootProject.property("keyAlias") as String
                keyPassword = rootProject.property("keyPassword") as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    if (devSignKey) {
        signingConfigs {
            register("debug_key") {
                storeFile = file(rootProject.property("dbgFilePath") as String)
                storePassword = rootProject.property("dbgPassword") as String
                keyAlias = rootProject.property("dbgKeyAlias") as String
                keyPassword = rootProject.property("dbgKeyPaswd") as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = if (devSignKey) {
                signingConfigs.getByName("sign_key")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (useSignKey) {
                signingConfigs.getByName("sign_key")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**",
                "META-INF/BC1024KE.SF",
                "META-INF/BC1024KE.DSA",
                "META-INF/BC2048KE.SF",
                "META-INF/BC2048KE.DSA"
            )
        }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.compose.ui.tooling)
    implementation(libs.recyclerview)
    implementation(libs.apksig)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    // MTDataFilesProvider
    //noinspection UseTomlInstead
    debugImplementation("com.github.L-JINBIN:MTDataFilesProvider:v1.0.0")
}