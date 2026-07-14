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
    // Drop the stray `logback.xml` shipped by `swagger-codegen-generators` so it can't become
    // the fat jar's default logback config. Our config is `easydblab-logback.xml`, so this
    // exclude only removes the dependency's copy.
    exclude("logback.xml")
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
            // Pin logback to our config by a unique name. On the installDist/distribution
            // classpath, `swagger-codegen-generators` (pulled in by the swaggerUI route) also
            // ships a `logback.xml`; with two `logback.xml` resources logback prints a WARN and
            // dumps its full status to the console on every run. A uniquely-named config makes
            // logback load ours via a single lookup and skip the duplicate scan entirely.
            "-Dlogback.configurationFile=easydblab-logback.xml",
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

// Test tiering: a fast UNIT tier (`test`) and a slow INTEGRATION tier (`integrationTest`),
// structurally separated by classpath scoping. `./gradlew test` runs ONLY the unit tier and
// needs no Docker; the integration tier (TestContainers, the Fabric8 mock server, real
// socket/SSH/Redis/K8s I/O) lives in `src/integrationTest/kotlin` and is wired into `check`
// below. The three integration-only test dependencies are scoped to this suite (see the
// `dependencies` block) so a container test can no longer even COMPILE under `src/test`.
testing {
    suites {
        // Pin both suites to the same JUnit Jupiter version as the `libs.testing` bundle so the
        // suite DSL doesn't fall back to its JUnit 4 default and pull a stray junit4 jar onto
        // either test classpath.
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.jupiter)
        }

        @Suppress("UnusedPrivateProperty")
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.jupiter)
        }
    }
}

// The integration tier reuses every dependency available to the unit tier (archunit,
// koin-test, coroutines-test, the testing bundle) so shared test deps live in exactly one
// place; the three integration-only deps are added on top in the `dependencies` block.
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

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

    // Testing — shared across BOTH tiers (unit `test` + `integrationTest`). The
    // integrationTest source set inherits these via `extendsFrom(testImplementation)`
    // wired below.
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    // Integration-tier-only dependencies. Deliberately scoped to `integrationTest` and NOT
    // to `testImplementation` so that a container / mock-server test can no longer COMPILE
    // under `src/test`. This is the structural enforcement of the tiering split: any test
    // that reaches for TestContainers, the Fabric8 mock server, or MockWebServer must live
    // in `src/integrationTest`.
    "integrationTestImplementation"(libs.bundles.testcontainers)
    "integrationTestImplementation"(libs.fabric8.kubernetes.server.mock)
    "integrationTestImplementation"(libs.okhttpMockwebserver)

    // The production code and the compiled output of the unit `test` source set (shared helpers
    // like BaseKoinTest, MockSSHClient, custom AssertJ assertions, TestPrompter) reach the
    // integration tier's compile AND runtime classpaths through the `associateWith(main)` /
    // `associateWith(test)` friend-path wiring below, so no explicit project()/test-output
    // dependency is declared here.
}

kotlin {
    // No fixed toolchain: compile with the developer's JDK (21 or newer, e.g. 25)
    // while pinning the bytecode target to 21 so artifacts run on older JVMs.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
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
    val integrationTest by getting {
        java.srcDirs("src/integrationTest/kotlin")
    }
}

// Give the integration tier the same `internal`-visibility friend paths the unit `test` tier
// gets for free. Kotlin only wires the `test` compilation as a friend of `main`; a custom
// source set does not get that association, so integration tests (moved out of `src/test`)
// could not see `internal` members of production code or of the shared unit-test helpers.
// Associating with both `main` and `test` restores that access AND puts their compiled output
// on the integration tier's compile + runtime classpaths, so no separate project()/test-output
// dependency is needed.
kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
    associateWith(kotlin.target.compilations.getByName("test"))
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

// Shared execution config for BOTH test tiers (`test` + `integrationTest`). The integration
// tier needs the same TestContainers/Docker environment as the unit tier, so configure every
// Test task in this project rather than just `test`. (`useJUnitPlatform()` is already set per
// suite in the `testing` block above.)
tasks.withType<Test>().configureEach {
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

// `./gradlew check` must run BOTH tiers; `./gradlew test` stays UNIT-ONLY (fast, no Docker).
tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
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

// Unit-test the pure build-plan logic behind the build-cassandra-ref workflow
// (version -> JDK / ant flags / base image / tags). No Docker, no network.
tasks.register<Exec>("testCassandraBuildPlan") {
    group = "Verification"
    description = "Unit-test the build-cassandra-ref build-plan resolution logic"
    workingDir = file(".")
    commandLine = listOf("bash", ".github/cassandra-image/resolve-build-plan.test.sh")
}

// Unit-test the ref-resolution logic behind the build-cassandra-ref workflow
// (ls-remote / raw-SHA fallback / fail-fast naming the bad ref). The network
// call is stubbed; no Docker, no real ls-remote.
tasks.register<Exec>("testCassandraResolveRef") {
    group = "Verification"
    description = "Unit-test the build-cassandra-ref ref-resolution logic"
    workingDir = file(".")
    commandLine = listOf("bash", ".github/cassandra-image/resolve-ref.test.sh")
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

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektMain") {
    baseline.set(file("config/detekt/baseline-main.xml"))
}
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektTest") {
    baseline.set(file("config/detekt/baseline-test.xml"))
}
tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detektIntegrationTest") {
    baseline.set(file("config/detekt/baseline-integration-test.xml"))
}

tasks.named<io.gitlab.arturbosch.detekt.Detekt>("detekt") {
    source(files("src/integrationTest/kotlin"))
}

// detekt 1.23.8 bundles a Kotlin compiler (2.0.21) that cannot run under a JDK 25
// runtime — its compiler front-end rejects the JDK version string, so the detekt tasks
// fail before analyzing anything. detekt's embedded compiler supports JDK <= 22, so when
// the build runs on a newer JDK we disable the detekt tasks rather than let `check`/`build`
// fail. CI runs on JDK 21, so static analysis is still enforced on every PR; a developer
// building on JDK 25 gets a clean `build`, just without local detekt feedback until CI runs
// it on a supported JDK. Remove this gate once detekt ships a stable JDK-25-capable release.
if (JavaVersion.current() > JavaVersion.VERSION_22) {
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        enabled = false
    }
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
