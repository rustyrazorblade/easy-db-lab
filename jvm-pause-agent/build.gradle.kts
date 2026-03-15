import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.ktlint)
}

group = "com.rustyrazorblade"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    archiveBaseName.set("jvm-pause-agent")
    manifest {
        attributes(
            "Premain-Class" to "com.rustyrazorblade.jvmpauseagent.JvmPauseAgent",
            "Can-Retransform-Classes" to "false",
            "Can-Redefine-Classes" to "false",
        )
    }
    // Relocate to avoid classloader conflicts with Cassandra's own OTel instrumentation
    relocate("io.opentelemetry", "com.rustyrazorblade.jvmpauseagent.shaded.opentelemetry")
    relocate("io.grpc", "com.rustyrazorblade.jvmpauseagent.shaded.grpc")
    relocate("com.google.protobuf", "com.rustyrazorblade.jvmpauseagent.shaded.protobuf")
    relocate("com.google.gson", "com.rustyrazorblade.jvmpauseagent.shaded.gson")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
