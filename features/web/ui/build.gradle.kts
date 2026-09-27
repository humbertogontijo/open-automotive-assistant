import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    id("com.github.node-gradle.node")
}

node {
    download.set(true)
    version.set(providers.gradleProperty("oaa.nodeVersion"))
    // The Node.js ivy repository is declared in settings.gradle.kts (FAIL_ON_PROJECT_REPOS).
    distBaseUrl.set(null as String?)
    npmInstallCommand.set("ci")
}

val buildWeb = tasks.register<NpmTask>("buildWeb") {
    group = "build"
    description = "Bundles the SPA into build/dist/web (minified, hashed, precompressed)."
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.dir("public")
    inputs.files("index.html", "build.mjs", "package.json", "package-lock.json")
    outputs.dir(layout.buildDirectory.dir("dist/web"))
}

tasks.register<NpmTask>("checkWeb") {
    group = "verification"
    description = "Type-checks, lints and unit-tests the SPA."
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "check"))
    inputs.dir("src")
    inputs.dir("test")
    inputs.files("jsconfig.json", "eslint.config.js", "package.json", "package-lock.json")
    outputs.upToDateWhen { false }
}

tasks.named("assemble") { dependsOn(buildWeb) }
tasks.named("check") { dependsOn("checkWeb") }
