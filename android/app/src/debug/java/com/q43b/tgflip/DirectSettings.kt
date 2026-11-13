package com.q43b.tgflip

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.drinkless.tdlib.TdApi
import java.io.File

// One Direct mode account's settings (prefs file "direct_<profile id>"): the user's own api_id and
// api_hash from my.telegram.org (never the owner's: CLAUDE.md), the proxy, where media files go, and
// whether it is logged in (remembered, so the chat list knows without starting TDLib).
// Folders: TDLib's database always in phone memory (noBackupFilesDir/tdlib_<id>/db: small, written all
// the time, must survive a removed card), its files on the SD card (the app's own folder there, no
// permission needed) or in phone memory, as chosen (docs/direct-mode-plan.md, Storage).
class DirectSettings(private val context: Context, val profileId: String) {
    private val prefs = context.getSharedPreferences("direct_$profileId", Context.MODE_PRIVATE)

    enum class ProxyKind { NONE, MTPROTO, SOCKS5 }

    data class Proxy(
        val kind: ProxyKind,
        val server: String = "",
        val port: Int = 0,
        val secret: String = "",  // MTProto
        val user: String = "",  // SOCKS5
        val password: String = "",
    ) {
        fun toTdlib(): TdApi.Proxy? = when (kind) {
            ProxyKind.NONE -> null
            ProxyKind.MTPROTO -> TdApi.Proxy(server, port, TdApi.ProxyTypeMtproto(secret))
            ProxyKind.SOCKS5 -> TdApi.Proxy(server, port, TdApi.ProxyTypeSocks5(user, password))
        }

        fun describe() = when (kind) {
            ProxyKind.NONE -> null
            ProxyKind.MTPROTO -> "MTProto $server:$port"
            ProxyKind.SOCKS5 -> "SOCKS5 $server:$port"
        }
    }

    var apiId: Int?
        get() = prefs.getInt("api_id", 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt("api_id", value ?: 0).apply()

    var apiHash: String
        get() = prefs.getString("api_hash", "").orEmpty()
        set(value) = prefs.edit().putString("api_hash", value).apply()

    val hasApi get() = apiId != null && apiHash.isNotEmpty()

    var proxy: Proxy
        get() = Proxy(
            runCatching { ProxyKind.valueOf(prefs.getString("proxy_kind", "NONE")!!) }.getOrDefault(ProxyKind.NONE),
            prefs.getString("proxy_server", "").orEmpty(),
            prefs.getInt("proxy_port", 0),
            prefs.getString("proxy_secret", "").orEmpty(),
            prefs.getString("proxy_user", "").orEmpty(),
            prefs.getString("proxy_password", "").orEmpty(),
        )
        set(p) = prefs.edit()
            .putString("proxy_kind", p.kind.name).putString("proxy_server", p.server).putInt("proxy_port", p.port)
            .putString("proxy_secret", p.secret).putString("proxy_user", p.user).putString("proxy_password", p.password)
            .apply()

    // Media on the SD card when there is one, unless the user chose phone memory
    var mediaOnSdChosen: Boolean
        get() = prefs.getBoolean("media_on_sd", true)
        set(value) = prefs.edit().putBoolean("media_on_sd", value).apply()

    var loggedIn: Boolean
        get() = prefs.getBoolean("logged_in", false)
        set(value) = prefs.edit().putBoolean("logged_in", value).apply()

    // "Anna Petrova (+7 900 …)", for the settings screen while TDLib is still starting
    var me: String
        get() = prefs.getString("me", "").orEmpty()
        set(value) = prefs.edit().putString("me", value).apply()

    val databaseDir get() = File(context.noBackupFilesDir, "tdlib_$profileId/db")

    private val phoneFilesDir get() = File(context.noBackupFilesDir, "tdlib_$profileId/files")

    private fun sdCard(): File? = context.getExternalFilesDirs(null).filterNotNull().firstOrNull {
        Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
    }

    val hasSdCard get() = sdCard() != null

    // Where TDLib keeps files now: the SD card when chosen and present, else phone memory
    val filesDir: File get() = sdCard()?.takeIf { mediaOnSdChosen }?.let { File(it, "tdlib_$profileId") } ?: phoneFilesDir

    val mediaOnSd get() = mediaOnSdChosen && hasSdCard

    // Everything this account has on the phone, for removing it
    fun allFolders(): List<File> = listOfNotNull(File(context.noBackupFilesDir, "tdlib_$profileId"),
        sdCard()?.let { File(it, "tdlib_$profileId") })

    fun clear() = prefs.edit().clear().apply()

    companion object {
        // tg://proxy?server=&port=&secret=, tg://socks?server=&port=[&user=&pass=], and the same as
        // https://t.me/proxy?… / https://t.me/socks?…; null when it isn't one
        fun parseLink(link: String): Proxy? {
            val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
            val kind = if (uri.scheme == "tg") uri.host else uri.pathSegments?.firstOrNull()
            val server = uri.getQueryParameter("server") ?: return null
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
            return when (kind) {
                "proxy" -> Proxy(ProxyKind.MTPROTO, server, port, secret = uri.getQueryParameter("secret") ?: return null)
                "socks" -> Proxy(ProxyKind.SOCKS5, server, port, user = uri.getQueryParameter("user").orEmpty(),
                    password = uri.getQueryParameter("pass").orEmpty())
                else -> null
            }
        }
    }
}
