plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.install"
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
    implementation(libs.kotlinx.coroutines.android)
}
