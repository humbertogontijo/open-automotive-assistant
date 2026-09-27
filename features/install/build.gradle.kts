plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.install"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Single source of truth: libs/signing/community.* → generated assets at build time
val communityAssets = layout.buildDirectory.dir("generated/communityAssets")
val copyCommunityKeys by tasks.registering(Copy::class) {
    from(
        rootProject.file("libs/signing/community.pk8"),
        rootProject.file("libs/signing/community.pem"),
    )
    into(communityAssets.map { it.dir("signing") })
}
android.sourceSets["main"].assets.srcDir(communityAssets)

tasks.named("preBuild").configure { dependsOn(copyCommunityKeys) }

dependencies {
    api(project(":integration-api"))
    api(project(":signing"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
