plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "app.mise"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mise"
        minSdk = 26
        targetSdk = 37
        // CI builds count up (GitHub run number) so each downloaded APK is a newer version.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed key for debug builds, committed on purpose: otherwise every machine (and every CI
        // run) signs with its own random key and Android refuses to install updates over the old app
        // ("App not installed"). It's a debug key for a personal, sideloaded app — not a secret.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Also check :core (pure JVM) against this app's minSdk — it runs on the phone too.
        checkDependencies = true
        warningsAsErrors = false
        // aapt only accepts <adaptive-icon> under mipmap-anydpi-v26, which lint calls redundant at minSdk 26.
        disable += "ObsoleteSdkInt"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads its Android jars from Maven Central; use Google's mirror (see settings.gradle.kts).
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.systemProperty("mise.repoRoot", rootProject.projectDir.parentFile.absolutePath)
            // ./gradlew :app:testDebugUnitTest -Pmise.screenshots=true  → refreshes docs/screenshots
            it.systemProperty("mise.writeScreenshots", (project.findProperty("mise.screenshots") ?: "false").toString())
            it.systemProperty("mise.e2eUrl", (project.findProperty("mise.e2eUrl") ?: "").toString())
            it.systemProperty("mise.e2eToken", (project.findProperty("mise.e2eToken") ?: "").toString())
        }
    }
}

// The bundled demo recipe is the shared fixture itself (single source of truth): fixtures/ is
// packaged as assets, i.e. assets/expected/*.json and assets/raw/*.txt.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(rootProject.projectDir.parentFile.resolve("fixtures").absolutePath)
        // Room's exported schemas, for the Robolectric migration test: Robolectric only reads the
        // variant's merged assets (not test assets), so debug builds carry these few KB of JSON.
        if (variant.buildType == "debug") {
            variant.sources.assets?.addStaticSourceDirectory("$projectDir/schemas")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
