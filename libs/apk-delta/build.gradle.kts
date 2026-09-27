plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("cc.opencar.assistant.apkdelta.ApkDeltaCliKt")
}

tasks.register<JavaExec>("diff") {
    group = "ota"
    description = "Write DIR/patch.oadp and DIR/apply.sh turning one APK into another. -Pold=… -Pnew=… -Pout=DIR"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("cc.opencar.assistant.apkdelta.ApkDeltaCliKt")
    workingDir = rootProject.projectDir
    val old = findProperty("old") as String?
    val new = findProperty("new") as String?
    val out = findProperty("out") as String?
    if (old != null && new != null && out != null) {
        args = listOf("diff", old, new, out)
    }
}
