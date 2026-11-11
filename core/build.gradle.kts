// The JVM target, `release(21)` and warnings-as-errors come from the root build's `subprojects`
// block.
plugins {
    idea
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    // Logging
    implementation(libs.bundles.logging)

    // Jackson
    implementation(libs.bundles.jackson)
}
