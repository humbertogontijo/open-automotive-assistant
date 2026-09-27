plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant.integrations.demo"
}

dependencies {
    api(project(":integration-api"))
    api(project(":integrations:platform:aaos"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}
