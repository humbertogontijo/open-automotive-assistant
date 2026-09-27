plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.opencar.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.opencar.assistant"
        minSdk = 30
        targetSdk = 34
        versionCode = providers.gradleProperty("oaa.versionCode").get().toInt()
        versionName = providers.gradleProperty("oaa.version").get()
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        create("contributor") {
            initWith(getByName("release"))
            isDebuggable = true
            applicationIdSuffix = ".contributor"
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
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
    implementation(project(":feature-telemetry"))
    implementation(project(":feature-web"))
    implementation(project(":feature-install"))
    implementation(project(":feature-dvr"))
    implementation(project(":feature-debug"))
    implementation(project(":feature-history"))
    implementation(project(":feature-shortcuts"))
    compileOnly(project(":car-stubs"))

    // Auto-wire vehicle integrations (integrations/<id>/)
    file("${rootProject.projectDir}/integrations").listFiles()
        ?.filter { it.isDirectory && it.name != "platform" && File(it, "build.gradle.kts").exists() }
        ?.sortedBy { it.name }
        ?.forEach { dir ->
            implementation(project(":integrations:${dir.name}"))
        }

    // Auto-wire plugins (plugins/<id>/ → :plugin-<id>)
    file("${rootProject.projectDir}/plugins").listFiles()
        ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
        ?.sortedBy { it.name }
        ?.forEach { dir ->
            implementation(project(":plugin-${dir.name}"))
        }

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
