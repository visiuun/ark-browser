import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives outside the repo: ~/.ark-signing/signing.properties (or the file named by
// ARK_SIGNING) points at the keystores, passwords and key-rotation lineage. Without it, release builds
// come out unsigned and debug builds use the normal Android debug key.
//
// Ark rotated its signing key in 1.6.0 (APK Signature Scheme v3). Every release is signed by the original
// key (so existing installs accept the update) plus the new key and the signed old->new lineage. Once a
// phone has a rotated build, Android 9+ refuses updates signed only with the old key.
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
        versionCode = 18
        versionName = "1.6.0"
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
            // Signed after assembly with apksigner (below): Gradle can't sign with a rotation lineage.
            signingConfig = null
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

// Sign each release APK with the old key, the new key and the rotation lineage:
// build/outputs/apk/<abi>/release/app-<abi>-release-unsigned.apk -> app-<abi>-release.apk
if (hasSigning) {
    afterEvaluate {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val tools = sdk.resolve("build-tools").listFiles()!!.filter { it.isDirectory }.maxBy { it.name }
        val apksigner = tools.resolve(if (System.getProperty("os.name").startsWith("Windows")) "apksigner.bat" else "apksigner")
        listOf("arm64", "x64").forEach { abi ->
            tasks.named("assemble${abi.replaceFirstChar { it.uppercase() }}Release") {
                doLast {
                    val dir = layout.buildDirectory.dir("outputs/apk/$abi/release").get().asFile
                    val unsigned = dir.resolve("app-$abi-release-unsigned.apk")
                    val out = dir.resolve("app-$abi-release.apk")
                    val cmd = listOf(
                        apksigner.absolutePath, "sign",
                        "--ks", signing.getProperty("old.store"), "--ks-key-alias", signing.getProperty("old.alias"),
                        "--ks-pass", "env:ARK_OLD_PASS", "--key-pass", "env:ARK_OLD_PASS",
                        "--next-signer",
                        "--ks", signing.getProperty("new.store"), "--ks-key-alias", signing.getProperty("new.alias"),
                        "--ks-pass", "env:ARK_NEW_PASS", "--key-pass", "env:ARK_NEW_PASS",
                        "--lineage", signing.getProperty("lineage"),
                        // Use the new key from Android 9 on (apksigner's default waits for Android 13).
                        "--rotation-min-sdk-version", "28",
                        "--out", out.absolutePath, unsigned.absolutePath,
                    )
                    val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                    pb.environment()["ARK_OLD_PASS"] = signing.getProperty("old.pass")
                    pb.environment()["ARK_NEW_PASS"] = signing.getProperty("new.pass")
                    val proc = pb.start()
                    val log = proc.inputStream.bufferedReader().readText()
                    if (proc.waitFor() != 0) throw GradleException("apksigner failed for $abi:\n$log")
                    logger.lifecycle("Signed ${out.name} (rotated key)")
                }
            }
        }
    }
}
