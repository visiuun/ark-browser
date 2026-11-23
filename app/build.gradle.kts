import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives outside the repo: ~/.ark-signing/signing.properties (or the file named by
// ARK_SIGNING) points at the keystore and password (new.store / new.alias / new.pass). Without it, release
// builds come out unsigned and debug builds use the normal Android debug key.
//
// Releases are signed with the new key ONLY, with no rotation lineage. 1.6.0 shipped a v3 rotation
// (old key -> new key), but then Android reports the original, leaked key as the app's identity
// (signingCertificateHistory[0], which androidx CallingAppInfo checks against privileged-browser allow
// lists) while Bitwarden & co. record the current key when you trust a browser. The two never match, so
// every passkey request failed with "browser signature does not match". Phones on 1.6.0 accept a
// new-key-only update and drop the old key from their history; phones still on 1.5.x or older must
// install 1.6.0 first (or reinstall).
val signing = Properties().apply {
    val f = file(System.getenv("ARK_SIGNING") ?: "${System.getProperty("user.home")}/.ark-signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = signing.getProperty("new.store") != null

android {
    namespace = "dev.vis.ark"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.vis.ark"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.6.2"
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
        if (hasSigning) create("ark") {
            storeFile = file(signing.getProperty("new.store"))
            storePassword = signing.getProperty("new.pass")
            keyAlias = signing.getProperty("new.alias")
            keyPassword = signing.getProperty("new.pass")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasSigning) signingConfigs.getByName("ark") else null
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("ark")
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
