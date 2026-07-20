// BurnPony crypto core: pure Kotlin/JVM, zero Android dependencies, zero
// third-party dependencies. Structured for eventual extraction/publication as
// BurnPonyCore-Kotlin (like CarrierPonyCore-Kotlin): everything public lives
// under com.burnpony.core with internal helpers kept internal.
//
// No jvmToolchain() here deliberately: the F-Droid build server runs with
// Gradle toolchain auto-detection/provisioning disabled, so the toolchain
// API cannot resolve a JDK there. Target 17 bytecode explicitly instead and
// compile with whatever JDK runs Gradle (17+ required by Gradle 9/AGP 9).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // The vector suite runs 600k-iteration PBKDF2 repeatedly; give it head room.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
    }
}
