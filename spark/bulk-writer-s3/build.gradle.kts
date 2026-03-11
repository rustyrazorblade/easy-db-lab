// Plugins, cassandra-analytics deps, Guava relocation, and shadow config
// are applied by the parent spark/build.gradle.kts for all bulk-writer-* modules.

application {
    mainClass.set("com.rustyrazorblade.easydblab.spark.S3BulkWriter")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("bulk-writer-s3")
    manifest {
        attributes["Main-Class"] = "com.rustyrazorblade.easydblab.spark.S3BulkWriter"
    }
}
