// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9 built-in Kotlin (Spec 32): org.jetbrains.kotlin.android is gone; AGP's built-in KGP is
// 2.2.10, so the Kotlin Gradle plugin on the build classpath is raised to the catalog pin.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}
