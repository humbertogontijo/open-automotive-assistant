plugins {
    kotlin("jvm")
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

val generatedDir = layout.buildDirectory.dir("generated/oaaBuild")

val generateOaaBuild by tasks.registering {
    val versionName = providers.gradleProperty("oaa.version")
    val versionCode = providers.gradleProperty("oaa.versionCode")
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    outputs.dir(generatedDir)
    doLast {
        val out = generatedDir.get().file("cc/opencar/assistant/protocol/OaaBuild.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            |package cc.opencar.assistant.protocol
            |
            |/** Generated from gradle.properties (`oaa.version`, `oaa.versionCode`). */
            |object OaaBuild {
            |    const val VERSION = "${versionName.get()}"
            |    const val VERSION_CODE = ${versionCode.get()}
            |}
            |""".trimMargin(),
        )
    }
}

sourceSets["main"].kotlin.srcDir(generateOaaBuild)

dependencies {
    // org.json ships with Android; the hub adds it as a runtime dependency.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
