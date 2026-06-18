import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    alias(libs.plugins.ktor)
}

// Configuration for OpenTelemetry Java agent
val otelAgentConfig: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val copyOtelAgent =
    tasks.register<Copy>("copyOtelAgent") {
        from(otelAgentConfig)
        into("${project.layout.buildDirectory.get()}/otel-agent")
        rename { "opentelemetry-javaagent.jar" }
    }

dependencies {
    otelAgentConfig("io.opentelemetry.javaagent:opentelemetry-javaagent:${libs.versions.opentelemetry.agent.get()}")
}

group = "com.rustyrazorblade"

tasks.withType<ShadowJar> {
    isZip64 = true
    mergeServiceFiles()
}

java {
    // Build with any JDK >= 21 (including 25), but emit Java 21 bytecode so the
    // output still runs on older JVMs. `release` is set on the compile tasks below
    // so the JDK-21 API surface is enforced even when compiling on a newer JDK.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    applicationName = "easy-db-lab"
    mainClass.set("com.rustyrazorblade.easydblab.MainKt")
    applicationDefaultJvmArgs =
        listOf(
            // NOTE: The OTel `-javaagent` flag is NOT declared here. Gradle 9's start-script
            // template runs DEFAULT_JVM_OPTS through an xargs|sed|eval pipeline that escapes
            // every `$`, so a `$APP_HOME` reference would reach java as a literal path and the
            // VM would abort on a missing agent jar. The agent flag is appended in the
            // `startScripts` doLast below, where `$APP_HOME` is expanded before that pipeline.
            "-Deasydblab.ami.name=rustyrazorblade/images/easy-db-lab-cassandra-amd64-$version",
            "-Deasydblab.version=$version",
        )
}

ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
    }
    fatJar {
        archiveFileName.set("${project.name}-${project.version}-all.jar")
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        // Update the Unix / Mac / Linux start script.
        //
        // Append a second DEFAULT_JVM_OPTS assignment so that `$APP_HOME` is expanded by the
        // shell (APP_HOME is already resolved at this point in the script) BEFORE Gradle's
        // xargs|sed|eval arg-splitting pipeline runs. Args placed in `applicationDefaultJvmArgs`
        // are treated literally by that pipeline, so `$APP_HOME` cannot be referenced there —
        // both the apphome system property and the OTel java agent path are injected here.
        val replacement =
            "\$1 \nDEFAULT_JVM_OPTS=\"\\\$DEFAULT_JVM_OPTS -Deasydblab.apphome=\\\$APP_HOME " +
                "-javaagent:\\\$APP_HOME/agents/opentelemetry-javaagent.jar\""
        val regex = "^(DEFAULT_JVM_OPTS=.*)".toRegex(RegexOption.MULTILINE)
        val body = unixScript.readText()
        val newBody = regex.replace(body, replacement)
        unixScript.writeText(newBody)

        // This needs to be updated for windows
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
    implementation(libs.commons.text)

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

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.kotlinx.coroutines.core)

    // MCP SDK and dependencies
    implementation(libs.mcp.sdk)
    implementation(libs.kotlinx.io)

    // Kubernetes
    implementation(libs.fabric8.kubernetes.client)

    // Redis (Event Bus)
    implementation(libs.jedis)

    // Cassandra Driver
    implementation(libs.cassandra.driver.core)
    implementation(libs.presto.jdbc)
    implementation(libs.trino.jdbc)
    implementation(libs.clickhouse.jdbc)
    implementation(libs.mysql.connector.j)
    implementation(libs.postgresql.jdbc)

    // Testing
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.fabric8.kubernetes.server.mock)
    testImplementation(libs.okhttpMockwebserver)
}

kotlin {
    // No fixed toolchain: compile with the developer's JDK (21 or newer, e.g. 25)
    // while pinning the bytecode target to 21 so artifacts run on older JVMs.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

sourceSets {
    val main by getting {
        java.srcDirs("src/main/kotlin")
        resources.srcDirs("build/aws", "dashboards")
    }
    val test by getting {
        java.srcDirs("src/test/kotlin")
    }
}

// Apply consistent test logging to every test task in every subproject.
allprojects {
    tasks.withType<Test> {
        testLogging {
            events("passed", "skipped", "failed", "standardError")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

tasks.test {
    useJUnitPlatform()

    // Enable HTML and XML reports
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }

    // Parallelism comes from forked JVMs, not in-JVM threads. Each fork has its own Koin
    // GlobalContext, so isolated tests can never collide on shared global state — which is
    // why in-JVM parallel execution is disabled in junit-platform.properties. forkEvery
    // reuses each JVM across many classes to amortise JVM startup cost.
    val processors = Runtime.getRuntime().availableProcessors()
    maxParallelForks = (processors / 2).coerceAtLeast(1)
    forkEvery = 100

    doFirst {
        environment("EASY_DB_LAB_PROFILE", "default")
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        // Rancher Desktop (and other Lima-based Docker runtimes) expose the Docker socket
        // at a host-userspace path (e.g. ~/.rd/docker.sock) that cannot be bind-mounted
        // inside containers from within the Lima VM. /var/run/docker.sock is the canonical
        // path that resolves correctly inside the VM.
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")

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
            // Include the OTel agent in the distribution
            from(copyOtelAgent) {
                into("agents")
            }
        }
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.named("installDist") {
    dependsOn(tasks.named("shadowJar"), copyOtelAgent)
    doLast {
        // Copy agent to installDist location
        copy {
            from("${project.layout.buildDirectory.get()}/otel-agent")
            into(layout.buildDirectory.dir("install/easy-db-lab/agents"))
        }
    }
}

tasks.assemble {
    mustRunAfter(tasks.clean)
}

detekt {
    baseline = file("config/detekt/baseline.xml")
}

// NOTE: detekt 1.23.x bundles a Kotlin compiler that cannot run under a JDK 25 runtime
// (its parser fails to map the JDK version to a JvmTarget). Run `detekt`/`check` under
// JDK 21 — which CI does — until detekt is upgraded to a JDK-25-capable release. Building
// and testing the application itself works fine on JDK 25.

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektMain") {
    baseline.set(file("config/detekt/baseline-main.xml"))
}
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektTest") {
    baseline.set(file("config/detekt/baseline-test.xml"))
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
        val imageTag = System.getProperty("jib.to.image.tag") ?: "latest"
        image = "ghcr.io/rustyrazorblade/easy-db-lab:$imageTag"
        tags = System.getProperty("jib.to.tags")?.split(",")?.toSet() ?: emptySet()
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
                "-javaagent:/agents/opentelemetry-javaagent.jar",
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
                path {
                    setFrom("${project.layout.buildDirectory.get()}/otel-agent")
                    into = "/agents"
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
    dependsOn(copyOtelAgent)
    notCompatibleWithConfigurationCache("Jib plugin doesn't fully support configuration cache yet")
}

tasks.named("jibDockerBuild") {
    dependsOn(copyOtelAgent)
    notCompatibleWithConfigurationCache("Jib plugin doesn't fully support configuration cache yet")
}
