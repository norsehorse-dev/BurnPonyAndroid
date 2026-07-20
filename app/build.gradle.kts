// BurnPony Android app module.
// AGP 9 built-in Kotlin: no separate Kotlin Android plugin. Moshi codegen via
// KSP only (house rule: never moshi-kotlin reflective, every DTO
// @JsonClass(generateAdapter = true), no KotlinJsonAdapterFactory).
//
// Phase 4: firebase-messaging is a standardImplementation dependency ONLY,
// and the com.google.gms google-services plugin is deliberately NOT applied —
// Firebase is initialized manually in the standard flavor's PushSupport from
// the google-services.json values. This keeps the foss dependency graph free
// of Google artifacts (verify: ./gradlew :app:dependencies --configuration
// fossReleaseRuntimeClasspath) and the build reproducible for F-Droid.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.burnpony.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.burnpony.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    buildTypes {
        release {
            // R8/shrinking configuration is Phase 6 (release prep, reproducible
            // builds for F-Droid). Off until then so every build is testable.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
