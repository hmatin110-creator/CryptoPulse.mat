plugins {
id("com.android.application")
id("org.jetbrains.kotlin.android")
id("org.jetbrains.kotlin.plugin.compose")
}

android {
namespace = "com.cryptopulse.app"
compileSdk = 35

defaultConfig {
    applicationId = "com.crypto110.app"
    minSdk = 26
    targetSdk = 35
    versionCode = 12
    versionName = "1.2.0"
}

compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlinOptions {
    jvmTarget = "17"
}

}

dependencies {
implementation("androidx.core:core-ktx:1.15.0")
implementation("androidx.activity:activity-compose:1.10.1")

implementation(platform("androidx.compose:compose-bom:2025.02.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.compose.material3:material3")

implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
implementation("androidx.work:work-runtime-ktx:2.10.1")

implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
implementation("com.squareup.moshi:moshi-kotlin:1.15.2")

implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

}
