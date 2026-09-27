plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

application {
    mainClass.set("cc.opencar.assistant.signing.SignApkCliKt")
}

tasks.register<JavaExec>("signApk") {
    group = "signing"
    description = "Sign an APK with the community testkey. -Pin=… -Pout=…"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("cc.opencar.assistant.signing.SignApkCliKt")
    workingDir = rootProject.projectDir
    systemProperty("oaa.signing.dir", rootProject.file("libs/signing").absolutePath)
    val input = findProperty("in") as String?
    val output = findProperty("out") as String?
    val key = findProperty("key") as String?
    val cert = findProperty("cert") as String?
    if (input != null) {
        val argsList = mutableListOf(input)
        if (output != null) argsList += output
        if (key != null) {
            argsList += "--key"
            argsList += key
        }
        if (cert != null) {
            argsList += "--cert"
            argsList += cert
        }
        args = argsList
    }
}
