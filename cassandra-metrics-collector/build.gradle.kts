plugins {
    idea
    java
    application
    kotlin("jvm")
    alias(libs.plugins.shadow)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.jib)
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

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "ghcr.io/rustyrazorblade/cassandra-metrics-collector"
        tags = (System.getProperty("jib.to.tags") ?: "latest").split(",").toSet()
        auth {
            username = System.getenv("GITHUB_ACTOR") ?: ""
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
    container {
        mainClass = "com.rustyrazorblade.cassandrametrics.MainKt"
        jvmFlags =
            listOf(
                "-Xmx256M",
            )
        environment =
            mapOf(
                "JAVA_TOOL_OPTIONS" to "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0",
            )
        ports = listOf("9601")
        creationTime = "USE_CURRENT_TIMESTAMP"
        filesModificationTime = "EPOCH_PLUS_SECOND"
        format = com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
        labels.set(
            mapOf(
                "org.opencontainers.image.source" to "https://github.com/rustyrazorblade/easy-db-lab",
                "org.opencontainers.image.description" to
                    "Cassandra virtual table metrics collector with Prometheus endpoint",
                "org.opencontainers.image.licenses" to "Apache-2.0",
            ),
        )
    }
}

tasks.named("jib") {
    notCompatibleWithConfigurationCache("Jib plugin doesn't fully support configuration cache yet")
}

tasks.named("jibDockerBuild") {
    notCompatibleWithConfigurationCache("Jib plugin doesn't fully support configuration cache yet")
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
