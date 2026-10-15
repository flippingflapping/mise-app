plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.json.schema.validator)
}

// The tests read the shared contract (schema + fixtures) straight from the repo root.
tasks.test {
    systemProperty("mise.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    inputs.dir(rootProject.projectDir.parentFile.resolve("schema"))
    inputs.dir(rootProject.projectDir.parentFile.resolve("fixtures"))
}
