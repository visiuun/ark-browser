plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vis.ark"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.vis.ark"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.5"
        buildConfigField("String", "UPDATE_REPO", "\"visiuun/ark-browser\"")
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("ark-release.jks")
            storePassword = "orbitbrowser"
            keyAlias = "orbit"
            keyPassword = "orbitbrowser"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version") }
    }
}

dependencies {
    val gecko = "160.0.20261104181552"
    "arm64Implementation"("org.mozilla.geckoview:geckoview-arm64-v8a:$gecko")
    "x64Implementation"("org.mozilla.geckoview:geckoview-x86_64:$gecko")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
