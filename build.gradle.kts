// Top-level build file where you can add configuration options common to all sub-projects/modules.
@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application") version "7.4.1" apply false
    id("com.android.library") version "7.4.1" apply false
    id("com.google.gms.google-services") version "4.3.13" apply false
    id("org.jetbrains.kotlin.android") version Version.kotlin apply false
    kotlin("plugin.serialization") version Version.kotlin apply false
}

tasks.register<Delete>("clean").configure {
    delete(rootProject.buildDir)
}

val apiCode by extra(93)
val verCode = Common.getBuildVersionCode(rootProject)

val verName = if (Version.isStable) {
    "v" + Version.officialVersionName + "-" + (Common.getGitHeadRefsSuffix(rootProject))
} else {
    "v" + Version.officialVersionName + "-preview-" + (Common.getGitHeadRefsSuffix(rootProject))
}

val androidTargetSdkVersion by extra(33)
val androidMinSdkVersion by extra(24)
val androidCompileSdkVersion by extra(33)
val androidBuildToolsVersion by extra("33.0.0")
val androidCompileNdkVersion = "23.1.7779620"

fun Project.configureBaseExtension() {
    extensions.findByType(com.android.build.gradle.BaseExtension::class)?.run {
        compileSdkVersion(androidCompileSdkVersion)
        ndkVersion = androidCompileNdkVersion
        buildToolsVersion = androidBuildToolsVersion

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion
            versionCode = verCode
            versionName = verName
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = true
        }

        packagingOptions.jniLibs.useLegacyPackaging = false
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureBaseExtension()
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
}
