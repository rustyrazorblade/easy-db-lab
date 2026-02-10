plugins {
    idea
    java
    application
    kotlin("jvm")
    alias(libs.plugins.shadow)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

group = "com.rustyrazorblade"

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "cassandra-metrics-collector"
    mainClass.set("com.rustyrazorblade.cassandrametrics.MainKt")
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Logging
    implementation(libs.bundles.logging)

    // CLI
    implementation(libs.picocli)

    // Cassandra Driver
    implementation(libs.cassandra.driver.core)

    // Ktor (HTTP server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

sourceSets {
    val integrationTest by creating {
        java {
            compileClasspath += sourceSets["main"].output + sourceSets["test"].output
            runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
            srcDir("src/integration-test/kotlin")
        }
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    description = "Runs integration tests with TestContainers"
    group = "Verification"
    testLogging {
        events("passed", "skipped", "failed")
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
