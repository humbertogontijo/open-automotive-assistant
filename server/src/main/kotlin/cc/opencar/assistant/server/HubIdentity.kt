package cc.opencar.assistant.server

import com.google.gson.Gson
import java.io.File
import java.net.InetAddress

/** Stable id and display name the hub presents to cars (`data/hub.json`). */
class HubIdentity(dataDir: File) {
    private data class Stored(val id: String, val name: String? = null)

    val id: String
    val name: String

    init {
        val file = File(dataDir, "hub.json")
        val stored = runCatching { Gson().fromJson(file.readText(), Stored::class.java) }.getOrNull()
        id = stored?.id?.takeIf { it.isNotBlank() } ?: ("hub-" + randomToken().take(12)).also {
            dataDir.mkdirs()
            file.writeText(Gson().toJson(Stored(it)))
        }
        name = System.getenv("OAA_HUB_NAME")?.trim()?.takeIf { it.isNotEmpty() }
            ?: stored?.name?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" }
            ?: "OAA hub"
    }
}
