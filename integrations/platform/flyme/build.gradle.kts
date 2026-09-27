plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant.integrations.platform.flyme"
}

dependencies {
    api(project(":integrations:platform:aaos"))
    api(project(":integration-api"))
}
