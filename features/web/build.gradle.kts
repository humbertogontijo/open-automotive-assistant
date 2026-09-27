plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.web"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/INDEX.LIST"
        resources.excludes += "/META-INF/io.netty.versions.properties"
        resources.excludes += "/META-INF/*.kotlin_module"
    }
}
dependencies {
    implementation(project(":protocol"))
    api(project(":integration-api"))
    api(project(":oaa-support"))
    api(project(":feature-debug"))
    api(project(":feature-install"))
    api(project(":feature-telemetry"))
    api(project(":feature-memory"))
    api(project(":feature-dvr"))
    api(project(":feature-history"))
    api(project(":feature-shortcuts"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Standard libwebrtc (SDP/ICE/DTLS) for the ADR-0003 media plane; no vendor signaling.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
