package com.q43b.tgflip

import android.app.Activity
import android.content.Context

// Direct mode as the main app sees it (DirectMode.kt): found by name, so release builds, which don't
// have this file, simply have no Direct mode
object DirectModeImpl : DirectMode {
    override fun openSetup(activity: Activity) = DirectSetupActivity.start(activity)

    override fun isLoggedIn(context: Context, profileId: String) = DirectSettings(context, profileId).loggedIn

    override fun forget(context: Context, profileId: String) = Tdlib.forget(context, profileId)
}
