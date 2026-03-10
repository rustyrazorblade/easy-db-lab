plugins {
    application
    id("com.gradleup.shadow")
}

val scalaVersion: String by extra

dependencies {
    // Shared module - exclude the unshaded Cassandra driver
    // since spark-cassandra-connector provides java-driver-core-shaded
    implementation(project(":spark:common")) {
        exclude(group = "org.apache.cassandra", module = "java-driver-core")
    }

    // Spark Cassandra Connector
    implementation("com.datastax.spark:spark-cassandra-connector_$scalaVersion:3.5.1")

    // JNR libraries required by the Cassandra Java Driver for native operations
    implementation("com.github.jnr:jnr-posix:3.1.15")
    implementation("com.github.jnr:jnr-ffi:2.2.11")
}

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.KeyValuePrefixCount")
}

tasks.shadowJar {
    archiveBaseName.set("connector-read-write")
    archiveClassifier.set("")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.KeyValuePrefixCount"
    }
}

tasks.jar {
    enabled = false
    dependsOn(tasks.shadowJar)
}
