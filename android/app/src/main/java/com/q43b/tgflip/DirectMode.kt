package com.q43b.tgflip

import android.app.Activity
import android.content.Context

// Direct mode (2.0, docs/direct-mode-plan.md): an account that talks to Telegram itself through TDLib
// instead of through our server. Until 2.0 ships, TDLib and all of Direct mode live in dev builds only
// (app/src/debug), so the main app reaches them through this interface: DirectModes.available is null
// in release builds, and everything Direct mode adds to the screens hides.
interface DirectMode {
    // The Direct account's own settings screen: api_id/api_hash, proxy, where media goes, log in/out
    fun openSetup(activity: Activity)

    // Did this account finish logging in to Telegram (as far as the phone remembers)?
    fun isLoggedIn(context: Context, profileId: String): Boolean

    // The Backend of a logged-in Direct account (starts TDLib for it when it isn't running)
    fun backend(context: Context): Backend

    // The account is being removed from the phone: log out of Telegram and delete its TDLib folders
    fun forget(context: Context, profileId: String)
}

object DirectModes {
    val available: DirectMode? by lazy {
        runCatching {
            Class.forName("com.q43b.tgflip.DirectModeImpl").getField("INSTANCE").get(null) as DirectMode
        }.getOrNull()
    }
}
