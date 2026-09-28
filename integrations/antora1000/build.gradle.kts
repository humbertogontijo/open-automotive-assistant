plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant.integrations.antora1000"
}

dependencies {
    api(project(":integration-api"))
    api(project(":integrations:platform:aaos"))
    api(project(":integrations:platform:flyme"))
    implementation(libs.kotlinx.coroutines.android)
    // VenusVehicleServer (unprivileged Antora path)
    implementation("io.grpc:grpc-okhttp:1.68.1")
    implementation("io.grpc:grpc-stub:1.68.1")
    testImplementation(libs.junit)
}
