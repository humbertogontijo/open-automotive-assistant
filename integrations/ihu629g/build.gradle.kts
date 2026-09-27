plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant.integrations.ihu629g"
}

dependencies {
    api(project(":integration-api"))
    api(project(":integrations:platform:aaos"))
    api(project(":integrations:platform:flyme"))
}
