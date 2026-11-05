plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.questrookie"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.questrookie"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    // Release = not debuggable: Compose runs compiled instead of in the interpreter, which a debug build forces and which
    // stalled the UI for seconds (input ANRs). Signed with the debug key so it installs over existing debug installs.
    buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
    // targetSdk 32 is deliberate (newer targets regress Quest permissions) and the app is sideloaded, not on Play.
    lint { disable += "ExpiredTargetSdkVersion" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "META-INF/*" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    // Streaming JSON reader: F-Droid's index is ~70 MB, too big for org.json, and android.util.JsonReader can't run in unit tests.
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; unit tests need the real one.
    testImplementation("org.json:json:20240303")
}
