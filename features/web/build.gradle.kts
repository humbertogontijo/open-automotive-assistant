plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cc.opencar.assistant.feature.web"
    lint { baseline = file("lint-baseline.xml") }
}

/** Copies a produced directory into a variant source set (assets, jniLibs). */
abstract class SyncGenerated : DefaultTask() {
    @get:InputFiles
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Android's asset merger strips a trailing `.gz`, so gzip variants are renamed to this. */
    @get:Input
    @get:Optional
    abstract val gzipSuffix: Property<String>

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        val gz = gzipSuffix.orNull
        fs.sync {
            from(source)
            into(outputDir)
            if (gz != null) rename("(.*)\\.gz$", "$1$gz")
        }
    }
}

val oaartcBinding = project(":oaartc").layout.buildDirectory.dir("binding")

val oaartcJni = tasks.register<SyncGenerated>("oaartcJni") {
    source.from(oaartcBinding.map { it.dir("jni") })
    source.builtBy(":oaartc:unpackBinding")
    outputDir.set(layout.buildDirectory.dir("generated/oaartc/jni"))
}

// The SPA is built by :webui (features/web/ui) and served from assets/web/.
val webAssets = tasks.register<SyncGenerated>("webAssets") {
    source.from(project(":webui").layout.buildDirectory.dir("dist"))
    source.builtBy(":webui:buildWeb")
    gzipSuffix.set(".gzip")
    outputDir.set(layout.buildDirectory.dir("generated/webui/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(oaartcJni, SyncGenerated::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(webAssets, SyncGenerated::outputDir)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":apk-delta"))
    api(project(":integration-api"))
    api(project(":oaa-support"))
    api(project(":feature-debug"))
    api(project(":feature-install"))
    api(project(":feature-memory"))
    api(project(":feature-dvr"))
    api(project(":feature-history"))
    api(project(":feature-shortcuts"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.okhttp)
    // ADR-0003 media plane: Pion (pure Go) via gomobile, built by :oaartc. Not libwebrtc,
    // whose org.webrtc classes clash with the ones GeckoView embeds.
    implementation(files(oaartcBinding.map { it.file("classes.jar") }).builtBy(":oaartc:unpackBinding"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
