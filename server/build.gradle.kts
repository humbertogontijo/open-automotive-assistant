plugins {
    kotlin("jvm")
    application
}

group = "cc.opencar.assistant"
version = providers.gradleProperty("oaa.version").get()

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("cc.opencar.assistant.server.HubMainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":apk-delta"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.org.json)
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("org.jmdns:jmdns:3.5.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter)
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
    dependsOn(":webui:buildWeb")
    from(project(":webui").layout.buildDirectory.dir("dist/web")) {
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
