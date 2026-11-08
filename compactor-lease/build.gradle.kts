import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The account compactor's lease agent and the conditional-write port it is built on.
//
// This module has no dependency on the root module. It ships as a shadow jar,
// `agents/compactor-lease.jar` in the distribution and `/agents` in the Jib image, which a stock
// JRE image runs in the `lease` container of the `account-compactor` pod. The root module uses
// the same classes: `S3ObjectStore` delegates its conditional writes here, and
// `observability compactor status` reads the lease with `LeaseRecord`. The in-memory adapter and
// the shared contract tests are test fixtures, so the root module's tests run them too.
plugins {
    java
    `java-test-fixtures`
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

java {
    // Build with any JDK >= 21, emit Java 21 bytecode, as the root module does.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.awssdk.s3)

    testFixturesImplementation(libs.junit.jupiter.all)
    testFixturesImplementation(libs.assertj.core)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.junit.jupiter.all)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.jupiter)
        }
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("compactor-lease.jar")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "com.rustyrazorblade.easydblab.compactorlease.LeaseAgentMainKt")
    }
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// detekt 1.23.8 cannot run under a JDK newer than 22; the root build gates its own tasks the same
// way, and CI runs `check` on JDK 21.
if (JavaVersion.current() > JavaVersion.VERSION_22) {
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        enabled = false
    }
}
