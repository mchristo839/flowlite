plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.utomat.flowlite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.utomat.flowlite"
        minSdk = 26
        targetSdk = 35
        // Minutes since 2026-01-01, so every build can install over the last one as an update.
        versionCode = ((System.currentTimeMillis() / 1000 - 1767225600) / 60).toInt()
        // Release builds pass -PflowliteVersion=X.Y.Z from the git tag.
        versionName = (project.findProperty("flowliteVersion") as String?) ?: "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // The key every installed copy is signed with. Updates only install over the app with the same key,
    // so release builds restore it from the ANDROID_KEYSTORE_B64 secret (.github/workflows/release.yml).
    signingConfigs {
        getByName("debug") {
            val key = rootProject.file("../keys/android-debug.keystore")
            if (key.exists()) storeFile = key
        }
    }
    buildTypes {
        // Signed with the same key as every installed copy, so it installs over them as an update.
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8@aar")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
