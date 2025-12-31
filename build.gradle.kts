import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    java
    idea
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.versions)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.jib)
}

group = "com.rustyrazorblade"

tasks.withType<ShadowJar> {
    isZip64 = true
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

application {
    applicationName = "easy-db-lab"
    mainClass.set("com.rustyrazorblade.easydblab.MainKt")
    applicationDefaultJvmArgs =
        listOf(
            "-Deasydblab.ami.name=rustyrazorblade/images/easy-db-lab-cassandra-amd64-$version",
            "-Deasydblab.version=$version",
        )
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        // Update the Unix / Mac / Linux start script
        val replacement = "\$1 \nDEFAULT_JVM_OPTS=\"\\\$DEFAULT_JVM_OPTS -Deasydblab.apphome=\\\$APP_HOME\""
        val regex = "^(DEFAULT_JVM_OPTS=.*)".toRegex(RegexOption.MULTILINE)
        val body = unixScript.readText()
        val newBody = regex.replace(body, replacement)
        unixScript.writeText(newBody)

        // This needs to be updated for windows
    }
}

// In this section you declare where to find the dependencies of your project
allprojects {
    repositories {
        mavenCentral()
    }
}

// In this section you declare the dependencies for your production and test code
dependencies {
    // Logging
    implementation(libs.bundles.logging)

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // CLI and UI
    implementation(libs.picocli)
    implementation(libs.picocli.shell.jline3)
    implementation(libs.jline)
    implementation(libs.mordant)

    // AWS SDK
    implementation(libs.bundles.awssdk)

    // Utilities
    implementation(libs.classgraph)
    implementation(libs.commons.io)

    // Docker
    implementation(libs.bundles.docker)

    // HTTP Client (OkHttp for SOCKS proxy support)
    implementation(libs.okhttp)

    // Project dependencies
    implementation(project(":core"))

    // Jackson
    implementation(libs.bundles.jackson)

    // SSH
    implementation(libs.bundles.sshd)

    // Resilience4j
    implementation(libs.bundles.resilience4j)

    // Ktor
    implementation(libs.bundles.ktor)

    // Koin Dependency Injection
    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)

    // Kotlinx Serialization for MCP
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // MCP SDK and dependencies
    implementation(libs.mcp.sdk)
    implementation(libs.kotlinx.io)

    // Kubernetes
    implementation(libs.fabric8.kubernetes.client)

    // Cassandra Driver
    implementation(libs.cassandra.driver.core)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testcontainers)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    val main by getting {
        java.srcDirs("src/main/kotlin")
        resources.srcDirs("build/aws")
    }
    val test by getting {
        java.srcDirs("src/test/kotlin")
    }

    val integrationTest by creating {
        java {
            compileClasspath += sourceSets["main"].output + sourceSets["test"].output
            runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
            srcDir("src/integration-test/kotlin")
        }
    }
}

// The integrationTest source set creates these configurations automatically
// We just need to make them extend from the test configurations
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    outputs.upToDateWhen { false }
    description = "Runs the full end to end tests. Will create a cluster in AWS. Errors might require manual cluster tear down."
    group = "Verification"
}

tasks.test {
    useJUnitPlatform()

    // Enable HTML and XML reports
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }

    // Configure parallel test execution
    val processors = Runtime.getRuntime().availableProcessors()
    maxParallelForks = (processors / 2).coerceAtLeast(1)

    // Show test execution times and detailed output
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    doFirst {
        println("========================================")
        println("Test Execution Configuration:")
        println("  Available processors: $processors")
        println("  Max parallel forks: $maxParallelForks")
        println("========================================")
    }
}

// Packer testing tasks
tasks.register<Exec>("testPackerBase") {
    group = "Verification"
    description = "Test base packer provisioning scripts using Docker"
    workingDir = file("packer")
    commandLine = listOf("docker", "compose", "up", "--force-recreate", "--remove-orphans", "--exit-code-from", "test-base", "test-base")
}

tasks.register<Exec>("testPackerCassandra") {
    group = "Verification"
    description = "Test Cassandra packer provisioning scripts using Docker"
    workingDir = file("packer")
    commandLine =
        listOf("docker", "compose", "up", "--force-recreate", "--remove-orphans", "--exit-code-from", "test-cassandra", "test-cassandra")
}

tasks.register("testPacker") {
    group = "Verification"
    description = "Run all packer provisioning tests"
    dependsOn("testPackerBase", "testPackerCassandra")
}

tasks.register<Exec>("testPackerScript") {
    group = "Verification"
    description = "Test a specific packer script (use -Pscript=path/to/script.sh)"
    workingDir = file("packer")
    doFirst {
        val scriptPath =
            project.findProperty("script")?.toString()
                ?: throw GradleException("Please specify script path with -Pscript=path/to/script.sh")
        commandLine = listOf("./test-script.sh", scriptPath)
    }
}

tasks.register("buildAll") {
    group = "Publish"
//    dependsOn("buildDeb")
//    dependsOn("buildRpm")
    dependsOn(tasks.named("distTar"))
}

distributions {
    main {
        // Include the "packer" directory in the distribution
        contents {
            from("packer") {
                into("packer")
            }
        }
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.assemble {
    mustRunAfter(tasks.clean)
}

// Kover code coverage configuration
kover {
    reports {
        filters {
            excludes {
                // Exclude test classes
                classes(
                    "*Test",
                    "*Test\$*",
                    "*.test.*",
                    "*Mock*",
                    // Generated classes
                    "*\$\$*",
                    "*_Factory",
                    "*_Impl",
                    "*.BuildConfig",
                )

                // Exclude test packages
                packages(
                    "*.test",
                    "*.mock",
                )
            }
        }

        // Configure verification rules
        // Current coverage: Line 34.08%, Branch 16.54%
        // TODO: Gradually increase these thresholds as more tests are added
        verify {
            rule("Minimal line coverage") {
                disabled = false

                bound {
                    minValue = 40 // Start at 30%, gradually increase
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }

            rule("Minimal branch coverage") {
                disabled = false

                bound {
                    minValue = 15 // Start at 15%, gradually increase
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

// Jib container image configuration
jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "ghcr.io/rustyrazorblade/easy-db-lab"
        // Allow workflow to control tags via -Djib.to.tags
        tags = (System.getProperty("jib.to.tags") ?: "latest").split(",").toSet()
        auth {
            username = System.getenv("GITHUB_ACTOR") ?: ""
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
    container {
        mainClass = "com.rustyrazorblade.easydblab.MainKt"
        appRoot = "/app"
        jvmFlags =
            listOf(
                "-Deasydblab.ami.name=rustyrazorblade/images/easy-db-lab-cassandra-amd64-$version",
                "-Deasydblab.version=$version",
                "-Deasydblab.apphome=/app",
                "-Xmx2048M",
            )
        environment =
            mapOf(
                "JAVA_TOOL_OPTIONS" to "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0",
            )
        extraDirectories {
            paths {
                path {
                    setFrom(file("packer"))
                    into = "/app/packer"
                }
            }
        }
        creationTime = "USE_CURRENT_TIMESTAMP"
        filesModificationTime = "EPOCH_PLUS_SECOND"
        format = com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
        labels.set(
            mapOf(
                "org.opencontainers.image.source" to "https://github.com/rustyrazorblade/easy-db-lab",
                "org.opencontainers.image.description" to "Tool to create Cassandra lab environments in AWS",
                "org.opencontainers.image.licenses" to "Apache-2.0",
                "org.opencontainers.image.version" to version.toString(),
                "com.rustyrazorblade.easy-db-lab.requires-docker" to "true",
            ),
        )
    }
}

// Jib doesn't fully support configuration cache yet
tasks.named("jib") {
    notCompatibleWithConfigurationCache("Jib plugin doesn't fully support configuration cache yet")
}

tasks.named("jibDockerBuild") {
    notCompatibleWithConfigurationCache("Jib plugin doesn't fully support configuration cache yet")
}
