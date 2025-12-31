plugins {
    java
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
    // Spark (provided - on EMR cluster)
    compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
    compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

    // Cassandra Driver for CQL setup
    implementation("org.apache.cassandra:java-driver-core:4.18.1")
}
