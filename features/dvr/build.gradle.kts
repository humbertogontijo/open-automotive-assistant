plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.dvr"
    lint { baseline = file("lint-baseline.xml") }
}
dependencies {
    api(project(":integration-api"))
    testImplementation(libs.junit)
}
