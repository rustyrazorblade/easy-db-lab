// Shared configuration for all Spark submodules
subprojects {
    apply(plugin = "java")

    val sparkVersion = "3.5.7"
    val scalaVersion = "2.12"

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    repositories {
        mavenLocal()
        mavenCentral()
    }

    // Make Spark version available to subprojects via extra properties
    extra["sparkVersion"] = sparkVersion
    extra["scalaVersion"] = scalaVersion

    // Cassandra Analytics version resolution (shared by bulk-writer modules)
    val analyticsDir = rootProject.projectDir.resolve(".cassandra-analytics")
    val analyticsPropsFile = analyticsDir.resolve("gradle.properties")
    val cassandraAnalyticsVersion: String = if (analyticsPropsFile.exists()) {
        analyticsPropsFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("version=") }
            .map { it.substringAfter("version=").trim() }
            .firstOrNull()
            ?: error("No 'version' property in $analyticsPropsFile")
    } else {
        logger.warn("cassandra-analytics not cloned; using placeholder version. Run bin/build-cassandra-analytics to build.")
        "0.0.0-NOT-BUILT"
    }
    extra["analyticsDir"] = analyticsDir
    extra["cassandraAnalyticsVersion"] = cassandraAnalyticsVersion

    dependencies {
        // Spark (provided - on EMR cluster)
        "compileOnly"("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
        "compileOnly"("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")
    }
}
