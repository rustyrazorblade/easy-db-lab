plugins {
    java
    application
}

val sparkVersion = "3.5.8"
val scalaVersion = "2.12"
val sparkCassandraConnectorVersion = "3.5.1"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Spark (provided scope for EMR - these are on the cluster)
    compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
    compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

    // Spark Cassandra Connector
    implementation("com.datastax.spark:spark-cassandra-connector_$scalaVersion:$sparkCassandraConnectorVersion")
}

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.KeyValuePrefixCount")
}

tasks.jar {
    // Create fat jar for EMR submission
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.KeyValuePrefixCount"
    }
}
