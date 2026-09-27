pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
        // Node.js for :webui (node-gradle download = true).
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "open-automotive-assistant"

include(":protocol")
project(":protocol").projectDir = file("libs/protocol")
include(":server")
project(":server").projectDir = file("server")
// APK delta patches: hub OTA, the car's updater and oaa-setup all use it.
include(":apk-delta")
project(":apk-delta").projectDir = file("libs/apk-delta")
// Web UI bundle (npm + esbuild); hub-only builds need it too.
include(":webui")
project(":webui").projectDir = file("features/web/ui")

// Hub-only builds (Docker image) skip the Android modules, which need an SDK.
if (System.getenv("OAA_HUB_ONLY") != "1") {
    include(":app")

    // Shared libraries under libs/
    include(":integration-api")
    project(":integration-api").projectDir = file("libs/api")

    include(":oaa-support")
    project(":oaa-support").projectDir = file("libs/oaa-support")

    include(":car-stubs")
    project(":car-stubs").projectDir = file("libs/car-stubs")

    include(":signing")
    project(":signing").projectDir = file("libs/signing")

    include(":oaartc")
    project(":oaartc").projectDir = file("libs/oaartc")

    include(":integrations:platform:aaos")
    include(":integrations:platform:flyme")
    project(":integrations:platform:aaos").projectDir = file("integrations/platform/aaos")
    project(":integrations:platform:flyme").projectDir = file("integrations/platform/flyme")

    // Vehicle integrations (integrations/<id>/, except platform/) and bridge plugins
    // (plugins/<id>/ as :plugin-<id>); :app depends on every one via oaa.autoModules.
    fun modulesIn(dir: String, prefix: String) = file(dir).listFiles().orEmpty()
        .filter { it.isDirectory && it.name != "platform" && File(it, "build.gradle.kts").exists() }
        .sortedBy { it.name }
        .map { d -> "$prefix${d.name}".also { include(it); project(it).projectDir = d } }
    gradle.extra["oaa.autoModules"] = modulesIn("integrations", ":integrations:") + modulesIn("plugins", ":plugin-")

    // Curated shell features under features/ (Gradle names stay :feature-<id>)
    listOf(
        "memory",
        "web",
        "install",
        "dvr",
        "debug",
        "history",
        "shortcuts",
    ).forEach { id ->
        val path = ":feature-$id"
        include(path)
        project(path).projectDir = file("features/$id")
    }
}
