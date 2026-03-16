import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
}

group = "com.rustyrazorblade"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // HdrHistogram for high-precision pause measurement
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    // OpenTelemetry SDK for metrics export via OTLP gRPC
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.semconv)
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    archiveBaseName.set("jvm-pause-agent")

    manifest {
        attributes(
            "Premain-Class" to "com.rustyrazorblade.jvmpauseagent.JvmPauseAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false",
        )
    }

    // Relocate to avoid classloader conflicts with the target JVM's own OTel instrumentation
    relocate("io.opentelemetry", "com.rustyrazorblade.jvmpauseagent.shaded.opentelemetry")
    relocate("io.grpc", "com.rustyrazorblade.jvmpauseagent.shaded.grpc")
    relocate("com.google.protobuf", "com.rustyrazorblade.jvmpauseagent.shaded.protobuf")
    relocate("org.HdrHistogram", "com.rustyrazorblade.jvmpauseagent.shaded.hdrhistogram")

    mergeServiceFiles()

    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")

    isZip64 = true
}

// Disable the plain jar task — the shadow JAR is the artifact
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
