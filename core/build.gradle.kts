import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    idea
    java
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    // Build with any JDK >= 21 (including 25), but emit Java 21 bytecode.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Logging
    implementation(libs.bundles.logging)

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Jackson
    implementation(libs.bundles.jackson)
}
