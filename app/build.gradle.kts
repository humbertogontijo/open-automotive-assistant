plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "cc.opencar.assistant"
        minSdk = 30
        targetSdk = 34
        versionCode = providers.gradleProperty("oaa.versionCode").get().toInt()
        versionName = providers.gradleProperty("oaa.version").get()
        // GeckoView ships ~50 MB of native code per ABI; head units are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            // Emulator ABI; head-unit installs pass -Poaa.arm64Only to skip ~90 MB of libs.
            if (!providers.gradleProperty("oaa.arm64Only").isPresent) {
                ndk { abiFilters += "x86_64" }
            }
        }
        create("contributor") {
            initWith(getByName("release"))
            isDebuggable = true
            applicationIdSuffix = ".contributor"
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { baseline = file("lint-baseline.xml") }
    packaging {
        // libxul.so is ~150 MB raw; compressed it is ~50 MB, which is what OTA and sideloads download.
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/INDEX.LIST"
        resources.excludes += "/META-INF/io.netty.versions.properties"
        // Keep Java ServiceLoader descriptors from integration/plugin AARs
        resources.merges += "META-INF/services/**"
    }
}

dependencies {
    implementation(project(":integration-api"))
    implementation(project(":oaa-support"))
    implementation(project(":feature-memory"))
    implementation(project(":feature-web"))
    implementation(project(":feature-install"))
    implementation(project(":feature-dvr"))
    implementation(project(":feature-debug"))
    implementation(project(":feature-history"))
    implementation(project(":feature-shortcuts"))
    implementation(project(":protocol"))

    // Vehicle integrations and plugins discovered by settings.gradle.kts
    @Suppress("UNCHECKED_CAST")
    (gradle.extra["oaa.autoModules"] as List<String>).forEach { implementation(project(it)) }

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // Pinned: bump with Firefox releases for security fixes. 154+ pulls androidx.core 1.19,
    // which needs Android Gradle plugin 9.1.
    implementation("org.mozilla.geckoview:geckoview:153.0.20260810162159")
    testImplementation(libs.junit)
}
