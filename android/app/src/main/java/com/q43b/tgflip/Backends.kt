package com.q43b.tgflip

import android.content.Context

// Which Backend the current account talks through: our server (ApiClient), or for a Direct mode account
// (dev builds until 2.0, docs/direct-mode-plan.md) TDLib on the phone
object Backends {
    fun create(context: Context): Backend {
        val direct = DirectModes.available
        return if (direct != null && Profiles.current(context).direct) direct.backend(context) else server(context)
    }

    // The current account's server, for what only a server has (Under the hood, its themes, admin
    // messages, the animated stickers capability)
    fun server(context: Context): ApiClient {
        val settings = ServerSettings(context)
        return ApiClient(settings.getAddress(), settings.getToken().orEmpty())
    }
}
