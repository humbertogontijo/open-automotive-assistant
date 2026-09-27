package cc.opencar.assistant.signing

import java.io.File

/**
 * Host CLI: `gradle :signing:signApk -Pin=app.apk [-Pout=out.apk]`
 * or `java -cp … cc.opencar.assistant.signing.SignApkCliKt <apk> [out] [--key k] [--cert c]`
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") {
        System.err.println(
            "Usage: SignApkCli <input.apk> [output.apk] [--key community.pk8] [--cert community.pem]",
        )
        kotlin.system.exitProcess(if (args.isEmpty()) 1 else 0)
    }

    var input: File? = null
    var output: File? = null
    var key: File? = null
    var cert: File? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--key" -> {
                key = File(args.getOrNull(++i) ?: usageError("--key needs a path"))
            }
            "--cert" -> {
                cert = File(args.getOrNull(++i) ?: usageError("--cert needs a path"))
            }
            else -> {
                if (input == null) {
                    input = File(a)
                } else if (output == null) {
                    output = File(a)
                } else {
                    usageError("Unexpected argument: $a")
                }
            }
        }
        i++
    }

    val inFile = input ?: usageError("missing input.apk")
    val outFile = output ?: ApkReSigner.defaultSignedPath(inFile)
    val keyFile = key ?: defaultKeyFile()
    val certFile = cert ?: defaultCertFile()

    if (!keyFile.isFile) {
        System.err.println("Key not found: ${keyFile.absolutePath}")
        kotlin.system.exitProcess(2)
    }
    if (!certFile.isFile) {
        System.err.println("Cert not found: ${certFile.absolutePath}")
        kotlin.system.exitProcess(2)
    }

    println("Signing ${inFile.path} → ${outFile.path}")
    val result = ApkReSigner.sign(inFile, outFile, keyFile, certFile)
    if (!result.ok) {
        System.err.println("Sign failed: ${result.message}")
        kotlin.system.exitProcess(3)
    }
    println("Signed APK: ${outFile.absolutePath}")
}

private fun usageError(msg: String): Nothing {
    System.err.println(msg)
    kotlin.system.exitProcess(1)
}

/** Prefer module-local signing/ keys, then walk up from cwd. */
private fun defaultKeyFile(): File = findSibling("community.pk8")

private fun defaultCertFile(): File = findSibling("community.pem")

private fun findSibling(name: String): File {
    val candidates = listOf(
        File("libs/signing", name),
        File("signing", name),
        File(name),
        File("../signing", name),
        File("../../libs/signing", name),
    )
    // Also resolve relative to this class's jar/module root when run via Gradle
    val fromProp = System.getProperty("oaa.signing.dir")
    if (fromProp != null) {
        val f = File(fromProp, name)
        if (f.isFile) return f
    }
    return candidates.firstOrNull { it.isFile }
        ?: File("libs/signing", name)
}
