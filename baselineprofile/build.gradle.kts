plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.nuvio.tv.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("distribution", "full")
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("release") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    // T1 (2026-09-05): no GMD here — AGP's snapshotter crashes this box's
    // swiftshader renderer; generation uses a manually-launched rooted AOSP
    // emulator + the connected path (useConnectedDevices below). The Shield
    // must never be adb-connected during generation. See
    // docs/project/baseline-profile-procedure-2026-08-31.md.
}

androidComponents {
    onVariants(selector().withBuildType("benchmark")) {
        // Only benchmark variant is used for baseline profile generation
    }
}

// T1: generation runs against whatever is adb-connected — the manually-launched
// rooted AOSP emulator (GMD snapshotting crashes this box's swiftshader; windowed
// -gpu host works — see campaign live state 2026-09-05). The Shield must NEVER
// be adb-connected during generation.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
