// Plugins, spark-cassandra-connector deps, and shadow config
// are applied by the parent spark/build.gradle.kts for all connector-* modules.

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.StandardConnectorWriter")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("connector-writer")
    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.StandardConnectorWriter"
    }
}
