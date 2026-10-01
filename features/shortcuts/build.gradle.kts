plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.shortcuts"
    lint { baseline = file("lint-baseline.xml") }
}
dependencies {
    api(project(":integration-api"))
    implementation(project(":oaa-support"))
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
