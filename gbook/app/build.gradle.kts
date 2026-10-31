import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gbook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gbook"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.6.1"
        manifestPlaceholders["appLabel"] = "GBook"
        // -Pdev installs as a separate "GBook Dev" app next to the Play Store one, for testing on a phone
        // whose GBook is signed with a different key. Google sign-in needs its own client (see docs/sync.md).
        if (project.hasProperty("dev")) {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "GBook Dev"
        }
    }
    // Release signing reads a local, git-ignored keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // A -Pdev release build is for measuring on a phone, so it's signed like the debug one.
            signingConfig = if (project.hasProperty("dev")) signingConfigs.getByName("debug") else signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Local sample books (git-ignored) are bundled into debug builds only, never into release APKs.
    // Pass -PnoSamples for a debug build without them (e.g. for public screenshots).
    if (!project.hasProperty("noSamples")) sourceSets["debug"].assets.srcDir("../../sample_books")
    // -Pdev release builds (never published: they install as com.gbook.dev) carry them too, for testing.
    if (project.hasProperty("dev") && !project.hasProperty("noSamples")) sourceSets["release"].assets.srcDir("../../sample_books")
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    testImplementation("junit:junit:4.13.2")
}
