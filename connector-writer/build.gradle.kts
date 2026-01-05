plugins {
    java
    application
    id("com.gradleup.shadow") version "9.3.0"
}

val sparkVersion = "3.5.7"
val scalaVersion = "2.12"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Shared data generation module - exclude the unshaded Cassandra driver
    // since spark-cassandra-connector provides java-driver-core-shaded
    implementation(project(":spark-shared")) {
        exclude(group = "org.apache.cassandra", module = "java-driver-core")
    }

    // Spark (provided scope for EMR - these are on the cluster)
    compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
    compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

    // Spark Cassandra Connector - the standard CQL-based connector
    implementation("com.datastax.spark:spark-cassandra-connector_$scalaVersion:3.5.1")

    // JNR libraries required by the Cassandra Java Driver for native operations
    // The java-driver-core-shaded doesn't bundle these, so we need to add them explicitly
    implementation("com.github.jnr:jnr-posix:3.1.15")
    implementation("com.github.jnr:jnr-ffi:2.2.11")
}

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.StandardConnectorWriter")
}

// Use shadow plugin for fat JAR
tasks.shadowJar {
    archiveBaseName.set("connector-writer")
    archiveClassifier.set("")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.StandardConnectorWriter"
    }
}

// Replace default jar with shadowJar
tasks.jar {
    enabled = false
    dependsOn(tasks.shadowJar)
}
