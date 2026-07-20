// BurnPony crypto core: pure Kotlin/JVM, zero Android dependencies, zero
// third-party dependencies. Structured for eventual extraction/publication as
// BurnPonyCore-Kotlin (like CarrierPonyCore-Kotlin): everything public lives
// under com.burnpony.core with internal helpers kept internal.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
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
