// BurnPony Android app module.
// AGP 9 built-in Kotlin: no separate Kotlin Android plugin. Moshi codegen via
// KSP only (house rule: never moshi-kotlin reflective, every DTO
// @JsonClass(generateAdapter = true), no KotlinJsonAdapterFactory).
//
// Firebase-messaging is a standardImplementation dependency ONLY, and the
// com.google.gms google-services plugin is deliberately NOT applied —
// Firebase is initialized manually in the standard flavor's PushSupport.
// This keeps the foss dependency graph free of Google artifacts (verify:
// ./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath)
// and the build reproducible for F-Droid.
//
// Phase 6 (release): R8 + resource shrinking ON for release builds;
// signing from keystore.properties (never committed — see .gitignore);
// dependenciesInfo blobs off in both APK and bundle so F-Droid rebuilds
// can verify byte-identically.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Release signing lives OUTSIDE the repo: app/keystore.properties with
// storeFile/storePassword/keyAlias/keyPassword. Absent file = unsigned
// release (what the F-Droid build server produces before verification).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("app/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.burnpony.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.burnpony.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "2.0.0"
    }

    // Distribution split, following the PGPony foss-flavor precedent:
    // standard = Play, FCM push receipts; foss = F-Droid, no Google
    // dependencies anywhere in the graph, receipts via the existing polling.
    flavorDimensions += "dist"
    productFlavors {
        create("standard") {
            dimension = "dist"
        }
        create("foss") {
            dimension = "dist"
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Clean clones (F-Droid and the repro gate) have no
            // keystore.properties, so fall back to debug signing. This keeps
            // the release APK signed (named app-foss-release.apk, not
            // -unsigned) and gives it the same ZIP alignment as the published
            // build, which byte-level reproducibility requires. F-Droid strips
            // this signature and copies the real one across.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            // Do not embed git VCS info: it differs between the dev tree and
            // the F-Droid clone and would break reproducibility.
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Reproducible builds (F-Droid): no Play dependency-info blobs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    // AppCompat is here ONLY for AppCompatDelegate.setApplicationLocales -
    // the per-app language API. No AppCompat themes, widgets or resource
    // overlay are used beyond the Theme.AppCompat parent that
    // AppCompatActivity requires. It is the canonical owner of per-app
    // locale state below API 33, which is most of our minSdk 24 range.
    implementation(libs.androidx.appcompat)
    // Saved copies of sent notes: the copy opens only after BiometricPrompt
    // (fingerprint, face or screen lock). Pure AndroidX, no Google Play
    // services, so it is fine in the foss flavor.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.zxing.core)

    // Standard flavor only: FCM for read-receipt push. Nothing Google in foss.
    "standardImplementation"(platform(libs.firebase.bom))
    "standardImplementation"(libs.firebase.messaging)

    testImplementation(libs.junit)
}
