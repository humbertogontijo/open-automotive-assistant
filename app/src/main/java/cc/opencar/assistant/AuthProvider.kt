package cc.opencar.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import cc.opencar.assistant.protocol.OaaCarAuth

/**
 * `adb shell content call --uri content://<applicationId>.auth --method mint --arg <name>`
 * issues a tool token for host scripts (`oaa-setup pair`). Only the shell user holds DUMP;
 * the framework does not check provider permissions on [call], so it is checked here.
 */
class AuthProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (ctx.checkCallingPermission(DUMP) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$DUMP required")
        }
        if (method != "mint") return null
        val name = arg?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: "adb"
        val (client, token) = OaaApp.instance.runtime.auth.store.addClient(OaaCarAuth.KIND_TOOL, name)
        return Bundle().apply {
            putString("token", token)
            putString("clientId", client.id)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val DUMP = "android.permission.DUMP"
    }
}
