// Shared configuration for all Spark submodules

val sparkVersion = "3.5.7"
val scalaVersion = "2.12"

// Cassandra Analytics paths (only needed by bulk-writer modules)
val analyticsDir = rootProject.projectDir.resolve(".cassandra-analytics")

// Common configuration for ALL subprojects
subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    repositories {
        mavenLocal()
        mavenCentral()
    }

    extra["sparkVersion"] = sparkVersion
    extra["scalaVersion"] = scalaVersion

    dependencies {
        // Spark (provided - on EMR cluster)
        "compileOnly"("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
        "compileOnly"("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")
    }

    tasks.named<Test>("test") {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            html.required.set(true)
            xml.required.set(true)
        }
    }
}

// Shared configuration for bulk-writer modules (cassandra-analytics deps + Guava relocation)
configure(subprojects.filter { it.name.startsWith("bulk-writer") }) {
    apply(plugin = "application")
    apply(plugin = "com.gradleup.shadow")

    val analyticsPropsFile = analyticsDir.resolve("gradle.properties")
    val cassandraAnalyticsVersion: String = if (analyticsPropsFile.exists()) {
        analyticsPropsFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("version=") }
            .map { it.substringAfter("version=").trim() }
            .firstOrNull()
            ?: error("No 'version' property in $analyticsPropsFile")
    } else {
        // Placeholder during configuration phase; compileJava will fail with clear deps errors.
        // To build bulk-writer modules, run: bin/build-cassandra-analytics
        "0.0.0-NOT-BUILT"
    }

    // Fail fast with a clear message when trying to compile without cassandra-analytics
    tasks.named("compileJava") {
        doFirst {
            if (!analyticsPropsFile.exists()) {
                error("cassandra-analytics not built. Run: bin/build-cassandra-analytics")
            }
        }
    }

    dependencies {
        "implementation"(project(":spark:common"))

        // Cassandra Analytics - published modules (from local Maven)
        "implementation"("org.apache.cassandra:cassandra-analytics-core_spark3_$scalaVersion:$cassandraAnalyticsVersion")
        "implementation"("org.apache.cassandra:cassandra-bridge_spark3_$scalaVersion:$cassandraAnalyticsVersion")
        "implementation"("org.apache.cassandra:cassandra-analytics-spark-converter_spark3_$scalaVersion:$cassandraAnalyticsVersion")

        // Cassandra Analytics - internal modules not published to Maven.
        // These JARs are built from source by bin/dev build-analytics (requires JDK 11).
        "implementation"(files("$analyticsDir/cassandra-five-zero/build/libs/five-zero.jar"))
        "implementation"(files("$analyticsDir/cassandra-five-zero-bridge/build/libs/five-zero-bridge.jar"))
        "implementation"(files("$analyticsDir/cassandra-five-zero-types/build/libs/five-zero-types.jar"))
        "implementation"(files("$analyticsDir/cassandra-analytics-spark-five-zero-converter/build/libs/five-zero-sparksql.jar"))

        // Guava 32.x - cassandra-analytics requires RangeMap which was added in Guava 14+
        "implementation"("com.google.guava:guava:32.1.3-jre")
    }

    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        mergeServiceFiles()

        // Relocate Guava to avoid conflicts with EMR's older Guava version
        relocate("com.google.common", "shaded.com.google.common")
        relocate("com.google.thirdparty", "shaded.com.google.thirdparty")
    }

    tasks.named<Jar>("jar") {
        enabled = false
        dependsOn(tasks.named("shadowJar"))
    }
}

// Shared configuration for connector modules (spark-cassandra-connector deps)
configure(subprojects.filter { it.name.startsWith("connector") }) {
    apply(plugin = "application")
    apply(plugin = "com.gradleup.shadow")

    dependencies {
        // Shared module - exclude the unshaded Cassandra driver
        // since spark-cassandra-connector provides java-driver-core-shaded
        "implementation"(project(":spark:common")) {
            exclude(group = "org.apache.cassandra", module = "java-driver-core")
        }

        // Spark Cassandra Connector
        "implementation"("com.datastax.spark:spark-cassandra-connector_$scalaVersion:3.5.1")

        // JNR libraries required by the Cassandra Java Driver for native operations
        "implementation"("com.github.jnr:jnr-posix:3.1.15")
        "implementation"("com.github.jnr:jnr-ffi:2.2.11")
    }

    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    tasks.named<Jar>("jar") {
        enabled = false
        dependsOn(tasks.named("shadowJar"))
    }
}
