package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaPorts
import java.io.File

fun main(args: Array<String>) {
    var dataDir = System.getenv("OAA_DATA") ?: "/data"
    var humanPort = System.getenv("OAA_PORT")?.toIntOrNull() ?: OaaPorts.HUMAN_DEFAULT
    var nodePort = System.getenv("OAA_NODE_PORT")?.toIntOrNull() ?: OaaPorts.NODE_DEFAULT
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--data" -> {
                dataDir = args.getOrNull(++i) ?: dataDir
            }
            "--port" -> {
                humanPort = args.getOrNull(++i)?.toIntOrNull() ?: humanPort
            }
            "--node-port" -> {
                nodePort = args.getOrNull(++i)?.toIntOrNull() ?: nodePort
            }
            "-h", "--help" -> {
                println("Usage: oaa-hub [--data DIR] [--port HUMAN_PORT] [--node-port NODE_PORT]")
                println("Env: OAA_PORT OAA_NODE_PORT OAA_DATA OAA_DEMO_NODE OAA_PUBLIC_NODE_URL OAA_SESSION_PATH OAA_ARTIFACTS_PATH OAA_HA_URL OAA_TURN_URLS OAA_TURN_SECRET")
                return
            }
        }
        i++
    }
    File(dataDir).mkdirs()
    val hub = OaaHubServer(dataDir = File(dataDir), humanPort = humanPort, nodePort = nodePort)
    hub.start(wait = true)
}
