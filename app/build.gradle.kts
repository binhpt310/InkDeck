import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.inkdeck"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.inkdeck"

        // Plan.md §2.3. targetSdk 28 because this is sideloaded — there is no Play mandate, and
        // staying at 28 keeps API 27 runtime behaviour predictable instead of opting into a
        // decade of compatibility shims the device will never exercise.
        minSdk = 26
        targetSdk = 28

        versionCode = 1
        versionName = "0.1.0"

        // The InkReader 6 is armeabi-v7a 32-bit only (Plan.md §0). Nothing here compiles native
        // code today, but this makes a dependency that quietly ships arm64-only .so files fail
        // at build time rather than at install time on the device.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core-eink"))
    implementation(project(":core-data"))
    implementation(project(":feature-terminal"))
    implementation(project(":feature-tasks"))
    implementation(project(":feature-telegram"))
    implementation(project(":feature-market"))
    implementation(project(":feature-ai"))

    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
