plugins {
    `java-library`
}

dependencies {
    // Cassandra Driver for CQL setup
    api("org.apache.cassandra:java-driver-core:4.19.2")

    // Test dependencies
    testImplementation(libs.bundles.testcontainers)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

tasks.test {
    useJUnitPlatform()
    // Spark integration tests need more time
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
}
