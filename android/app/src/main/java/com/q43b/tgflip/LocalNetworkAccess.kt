package com.q43b.tgflip

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

// From Android 17 (API 37), an app built for it may reach devices on the home network (like the
// PC running the server) only with the "local network" permission, which the user allows in a
// dialog, like the microphone. Without it every request to a home address simply fails. The
// Public internet mode doesn't need it; older Android versions don't have it at all.
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17 = 37

    fun missing(context: Context, mode: ConnectionMode) =
        Build.VERSION.SDK_INT >= ANDROID_17 &&
            mode != ConnectionMode.PUBLIC &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED
}
