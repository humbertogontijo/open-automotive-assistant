plugins {
    kotlin("jvm")
    application
}

group = "cc.opencar.assistant"
version = providers.gradleProperty("oaa.version").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("cc.opencar.assistant.server.HubMainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("io.ktor:ktor-server-partial-content:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.json:json:20240303")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("org.jmdns:jmdns:3.5.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "cc.opencar.assistant.server.HubMainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// Bundle the shared SPA and common UI strings into the hub classpath.
tasks.processResources {
    from("${rootProject.projectDir}/features/web/src/main/assets/web") {
        into("web")
    }
    from("${rootProject.projectDir}/libs/oaa-support/src/main/assets/i18n/common") {
        into("i18n/common")
    }
}

tasks.register<JavaExec>("runHub") {
    group = "application"
    description = "Run the OAA hub server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("cc.opencar.assistant.server.HubMainKt")
    args("--data", System.getenv("OAA_DATA") ?: "build/oaa-data")
}
