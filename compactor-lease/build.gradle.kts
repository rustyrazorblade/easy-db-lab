import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The account compactor's lease agent and the conditional-write port it is built on.
//
// This module has no dependency on the root module. It ships as a shadow jar,
// `agents/compactor-lease.jar` in the distribution and `/agents` in the Jib image, which a stock
// JRE image runs in the `lease` container of the `account-compactor` pod. The root module uses
// the same classes: `CompactorLeaseReader` reads the lease with `S3ConditionalObjects`, and
// `observability compactor status` reads the lease with `LeaseRecord`. The in-memory adapter and
// the shared contract tests are test fixtures, so the root module's tests run them too.
//
// The JVM target, `release(21)`, warnings-as-errors and the detekt JDK gate come from the root
// build's `subprojects` block.
plugins {
    `java-test-fixtures`
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

dependencies {
    implementation(libs.bundles.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.awssdk.s3)

    testFixturesImplementation(libs.junit.jupiter.all)
    testFixturesImplementation(libs.assertj.core)

    testImplementation(libs.bundles.testing)
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
