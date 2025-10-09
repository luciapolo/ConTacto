plugins {
    id("com.android.application")
    kotlin("android")
}


android {
    namespace = "com.example.elder"
    compileSdk = 35


    defaultConfig {
        applicationId = "com.example.elder"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }


    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
}


dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.3")


// Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")


// Voice
    implementation("androidx.core:core-ktx:1.13.1")


// Permissions helper
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")
}