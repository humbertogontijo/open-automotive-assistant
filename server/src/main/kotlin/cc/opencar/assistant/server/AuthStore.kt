package cc.opencar.assistant.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import org.mindrot.jbcrypt.BCrypt
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class HubRole(val wire: String) {
    @SerializedName("admin") ADMIN("admin"),
    @SerializedName("user") USER("user"),
    @SerializedName("system") SYSTEM("system"),
}

data class HubUser(
    val id: String,
    var username: String,
    var passwordHash: String?,
    var displayName: String,
    var role: HubRole = HubRole.USER,
    var haUserId: String? = null,
) {
    val isAdmin: Boolean get() = role == HubRole.ADMIN || role == HubRole.SYSTEM
}

data class HubSession(
    val token: String,
    val userId: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

private data class AuthSnapshot(
    val users: List<HubUser> = emptyList(),
    val sessions: List<HubSession> = emptyList(),
)

/**
 * Hub human auth: local username/password, Home Assistant identities (OAuth or
 * Ingress). HA identities are matched by HA user id only, never by username.
 */
class AuthStore(private val dataDir: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val storeFile = File(dataDir, "auth.json")
    private val users = ConcurrentHashMap<String, HubUser>()
    private val sessions = ConcurrentHashMap<String, HubSession>()

    init {
        load()
        ensureSystemUser()
    }

    fun needsSetup(): Boolean = users.values.none { it.role == HubRole.ADMIN }

    @Synchronized
    fun createAdmin(username: String, password: String, displayName: String = username): HubUser {
        require(needsSetup()) { "setup already complete" }
        val name = username.trim().lowercase()
        require(name.isNotBlank() && password.length >= 4) { "invalid credentials" }
        val user = HubUser(
            id = UUID.randomUUID().toString(),
            username = name,
            passwordHash = BCrypt.hashpw(password, BCrypt.gensalt()),
            displayName = displayName.ifBlank { name },
            role = HubRole.ADMIN,
        )
        users[user.id] = user
        persist()
        return user
    }

    fun loginLocal(username: String, password: String): HubSession? {
        val name = username.trim().lowercase()
        val user = users.values.firstOrNull { it.username == name } ?: return null
        val hash = user.passwordHash ?: return null
        if (!BCrypt.checkpw(password, hash)) return null
        return issueSession(user.id)
    }

    /**
     * Session for a Home Assistant identity. The first identity on a hub without
     * an admin becomes admin; HA administrators map to hub admins.
     */
    @Synchronized
    fun loginHa(haUserId: String, username: String, displayName: String, haAdmin: Boolean = false): HubSession {
        val user = users.values.firstOrNull { it.haUserId == haUserId } ?: run {
            val base = username.trim().lowercase().ifBlank { "ha-$haUserId" }
            HubUser(
                id = UUID.randomUUID().toString(),
                username = uniqueUsername(base),
                passwordHash = null,
                displayName = displayName.ifBlank { base },
                role = if (needsSetup() || haAdmin) HubRole.ADMIN else HubRole.USER,
                haUserId = haUserId,
            ).also {
                users[it.id] = it
                persist()
            }
        }
        return sessions.values.firstOrNull { it.userId == user.id } ?: issueSession(user.id)
    }

    fun issueSession(userId: String): HubSession {
        val session = HubSession(token = randomToken(), userId = userId)
        sessions[session.token] = session
        persist()
        return session
    }

    fun resolveSession(token: String?): HubUser? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        return users[session.userId]
    }

    fun logout(token: String?) {
        if (token.isNullOrBlank()) return
        if (sessions.remove(token) != null) persist()
    }

    fun get(userId: String): HubUser? = users[userId]

    fun systemToken(): String {
        val sys = users.values.first { it.role == HubRole.SYSTEM }
        return sessions.values.firstOrNull { it.userId == sys.id }?.token ?: issueSession(sys.id).token
    }

    private fun uniqueUsername(base: String): String {
        val taken = users.values.map { it.username }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun ensureSystemUser() {
        if (users.values.any { it.role == HubRole.SYSTEM }) return
        val user = HubUser(
            id = "system",
            username = "system",
            passwordHash = null,
            displayName = "System",
            role = HubRole.SYSTEM,
        )
        users[user.id] = user
        persist()
    }

    @Synchronized
    private fun load() {
        if (!storeFile.exists()) return
        val snap = runCatching { gson.fromJson(storeFile.readText(), AuthSnapshot::class.java) }.getOrNull() ?: return
        users.clear()
        sessions.clear()
        snap.users.forEach { users[it.id] = it }
        snap.sessions.forEach { sessions[it.token] = it }
    }

    @Synchronized
    private fun persist() {
        dataDir.mkdirs()
        storeFile.writeText(gson.toJson(AuthSnapshot(users.values.toList(), sessions.values.toList())))
    }
}
