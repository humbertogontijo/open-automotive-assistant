plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.history"
}
dependencies {
    api(project(":integration-api"))
    implementation(libs.kotlinx.coroutines.android)
}
