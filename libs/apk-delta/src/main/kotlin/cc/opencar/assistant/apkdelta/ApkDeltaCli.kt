package cc.opencar.assistant.apkdelta

import java.io.File
import kotlin.system.exitProcess

/**
 * Host CLI: `gradle :apk-delta:diff -Pold=base.apk -Pnew=target.apk -Pout=DIR`
 * or `java -cp … cc.opencar.assistant.apkdelta.ApkDeltaCliKt diff|apply …`
 *
 * `diff` writes DIR/patch.oadp and DIR/apply.sh and prints `patch=<bytes> full=<bytes> ops=<n>`.
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "diff" -> {
            if (args.size != 4) usage()
            val old = File(args[1])
            val new = File(args[2])
            val dir = File(args[3]).also { it.mkdirs() }
            val patch = File(dir, "patch.oadp")
            val header = runCatching { ApkDelta.diff(old, new, patch) }.getOrElse {
                System.err.println("diff failed: ${it.message}")
                exitProcess(2)
            }
            File(dir, "apply.sh").writeText(ApkDelta.shellScript(header))
            println("patch=${patch.length()} full=${new.length()} ops=${header.ops.size}")
        }
        "apply" -> {
            if (args.size != 4) usage()
            val out = File(args[3])
            val header = runCatching { ApkDelta.apply(File(args[1]), File(args[2]), out) }.getOrElse {
                System.err.println("apply failed: ${it.message}")
                exitProcess(2)
            }
            if (ApkDelta.sha256(out) != header.toSha256) {
                System.err.println("apply failed: sha256 mismatch")
                exitProcess(3)
            }
            println("sha256=${header.toSha256}")
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("Usage: ApkDeltaCli diff <old.apk> <new.apk> <outDir> | apply <base.apk> <patch.oadp> <out.apk>")
    exitProcess(1)
}
