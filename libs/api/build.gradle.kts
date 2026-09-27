plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant.api"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}
