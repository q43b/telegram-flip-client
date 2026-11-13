package com.q43b.tgflip

import android.content.Context

// Which Backend the current account talks through. Until Direct mode exists (docs/direct-mode-plan.md,
// step 3) every account talks to our server.
object Backends {
    fun create(context: Context): Backend = server(context)

    // The current account's server, for what only a server has (Under the hood, its themes, admin
    // messages, the animated stickers capability)
    fun server(context: Context): ApiClient {
        val settings = ServerSettings(context)
        return ApiClient(settings.getAddress(), settings.getToken().orEmpty())
    }
}
