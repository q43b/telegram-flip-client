package com.q43b.tgflip

import androidx.annotation.StringRes

// How the phone reaches the server. Each mode keeps its own address; the token is shared.
// The rest of the app only asks ServerSettings for "the address", so a new way of
// connecting is one more entry here. `scheme` is the only thing deciding plain HTTP vs
// HTTPS: network_security_config.xml allows plain HTTP to any host, because it cannot
// follow the addresses entered in Settings.
//   LOCAL:  home Wi-Fi, plain HTTP to the PC's LAN IP.
//   VPN:    through Tailscale (or another VPN). Plain HTTP, but inside the VPN's
//           encrypted tunnel, so nothing travels readable on the internet.
//   PUBLIC: over the open internet (public IP, tunnel or rented server). HTTPS only:
//           with plain HTTP the token and messages would travel readable.
enum class ConnectionMode(
    val key: String,
    @StringRes val label: Int,
    @StringRes val shortLabel: Int,
    val scheme: String,
    val defaultHost: String,
    val defaultPort: Int,
) {
    LOCAL("local", R.string.mode_local, R.string.mode_local_short, "http", "", 9436),
    VPN("vpn", R.string.mode_vpn, R.string.mode_vpn_short, "http", "", 9436),
    PUBLIC("public", R.string.mode_public, R.string.mode_public_short, "https", "", 443);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: LOCAL
    }
}
