import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use {
            load(it)
        }
    }
}

val discogsToken =
    localProperties.getProperty("DISCOGS_TOKEN") ?: ""

val backendBaseUrl =
    (
        localProperties.getProperty("BACKEND_BASE_URL")
            ?: "http://10.0.2.2:8000/"
    ).let { url ->
        if (url.endsWith("/")) url else "$url/"
    }

android {
    namespace = "com.example.discogsandroidapp"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.discogsandroidapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "DISCOGS_TOKEN",
            "\"$discogsToken\""
        )

        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"$backendBaseUrl\""
        )

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Retrofit for network requests
    implementation("com.squareup.retrofit2:retrofit:2.11.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(
        "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0"
    )

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Material icons
    implementation("androidx.compose.material:material-icons-extended")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Google Play Services Code Scanner
    implementation(
        "com.google.android.gms:play-services-code-scanner:16.1.0"
    )
}