import java.net.URI
import java.util.Properties

// Builds the Pion-based WebRTC binding (oaartc.go) with gomobile. The Go toolchain is
// downloaded once into the Gradle user home, like the Node plugin does for Node, so
// contributors only need the Android SDK plus the pinned NDK.
plugins {
    base
}

val goVersion = providers.gradleProperty("oaa.goVersion").get()
val mobileVersion = providers.gradleProperty("oaa.goMobileVersion").get()
val ndkVersion = providers.gradleProperty("oaa.ndkVersion").get()
/** armeabi-v7a stays in the binding so 32-bit head units only need an ABI filter change. */
val bindTargets = "android/arm64,android/arm,android/amd64"

val toolchainDir = gradle.gradleUserHomeDir.resolve("oaa-toolchains/go$goVersion")
val goRoot = toolchainDir.resolve("go")
val goBin = toolchainDir.resolve("bin")
val goPath = gradle.gradleUserHomeDir.resolve("oaa-toolchains/gopath")

fun hostArchive(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val goOs = when {
        os.contains("mac") -> "darwin"
        os.contains("linux") -> "linux"
        else -> throw GradleException("oaartc: unsupported build host '$os' (need macOS or Linux)")
    }
    val goArch = when (arch) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "amd64"
        else -> throw GradleException("oaartc: unsupported build CPU '$arch'")
    }
    return "go$goVersion.$goOs-$goArch.tar.gz"
}

fun androidSdkDir(): File {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val path = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("oaartc: set sdk.dir in local.properties or ANDROID_HOME")
    return File(path)
}

fun goEnv(): Map<String, String> {
    val sdk = androidSdkDir()
    val ndk = sdk.resolve("ndk/$ndkVersion")
    if (!ndk.isDirectory) {
        throw GradleException("oaartc: NDK $ndkVersion missing; run sdkmanager \"ndk;$ndkVersion\"")
    }
    return mapOf(
        "PATH" to "${goBin.absolutePath}:${goRoot.resolve("bin").absolutePath}:${System.getenv("PATH")}",
        "GOROOT" to goRoot.absolutePath,
        "GOPATH" to goPath.absolutePath,
        "GOBIN" to goBin.absolutePath,
        "GOTOOLCHAIN" to "local",
        "GOFLAGS" to "-mod=readonly",
        "ANDROID_HOME" to sdk.absolutePath,
        "ANDROID_NDK_HOME" to ndk.absolutePath,
    )
}

val setupGo by tasks.registering {
    description = "Downloads the pinned Go toolchain into the Gradle user home."
    outputs.dir(goRoot)
    onlyIf { !goRoot.resolve("bin/go").exists() }
    doLast {
        val archive = hostArchive()
        toolchainDir.mkdirs()
        val tmp = toolchainDir.resolve(archive)
        URI("https://go.dev/dl/$archive").toURL().openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val proc = ProcessBuilder("tar", "-xzf", tmp.absolutePath, "-C", toolchainDir.absolutePath)
            .inheritIO().start()
        if (proc.waitFor() != 0) throw GradleException("oaartc: extracting $archive failed")
        tmp.delete()
    }
}

val setupGomobile by tasks.registering(Exec::class) {
    description = "Installs the pinned gomobile and gobind."
    dependsOn(setupGo)
    outputs.file(goBin.resolve("gomobile"))
    onlyIf { !goBin.resolve("gomobile").exists() || !goBin.resolve("gobind").exists() }
    doFirst { environment(goEnv()) }
    workingDir = projectDir
    commandLine(
        goRoot.resolve("bin/go").absolutePath, "install",
        "golang.org/x/mobile/cmd/gomobile@$mobileVersion",
        "golang.org/x/mobile/cmd/gobind@$mobileVersion",
    )
}

val aar = layout.buildDirectory.file("bind/oaartc.aar")

val bindAndroid by tasks.registering(Exec::class) {
    description = "Builds oaartc.aar with gomobile bind."
    dependsOn(setupGomobile)
    inputs.files(fileTree(projectDir) { include("*.go", "go.mod", "go.sum") })
    inputs.property("targets", bindTargets)
    inputs.property("goVersion", goVersion)
    outputs.file(aar)
    doFirst {
        aar.get().asFile.parentFile.mkdirs()
        environment(goEnv())
    }
    workingDir = projectDir
    commandLine(
        goBin.resolve("gomobile").absolutePath, "bind",
        "-target=$bindTargets",
        "-androidapi", "21",
        "-javapkg", "cc.opencar.oaartc",
        // pion's Android interface lookup (wlynxg/anet) links net.zoneCache, which Go 1.23+
        // rejects unless linkname checks are off.
        "-trimpath", "-ldflags=-s -w -checklinkname=0",
        "-o", aar.get().asFile.absolutePath,
        ".",
    )
}

/** classes.jar for compilation plus jni/<abi>/libgojni.so for packaging. */
val unpackBinding by tasks.registering(Sync::class) {
    dependsOn(bindAndroid)
    from(zipTree(aar)) {
        include("classes.jar", "jni/**")
    }
    into(layout.buildDirectory.dir("binding"))
}

tasks.named("assemble") { dependsOn(unpackBinding) }
