plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant.integrations.aaos"
}

dependencies {
    api(project(":integration-api"))
    api(project(":oaa-support"))
    compileOnly(project(":car-stubs"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}

