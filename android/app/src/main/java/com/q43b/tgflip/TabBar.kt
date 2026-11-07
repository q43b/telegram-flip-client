package com.q43b.tgflip

import android.app.Activity
import android.graphics.Color
import android.widget.TextView

enum class Tab { CHATS, SETTINGS }

// Highlights the active tab in tab_bar.xml. The d-pad switches tabs (Right = Settings,
// Left = back to Chats); tapping the other tab does the same on a touchscreen.
fun Activity.setUpTabBar(active: Tab, openOther: () -> Unit) {
    val tabs = mapOf(Tab.CHATS to R.id.tab_chats, Tab.SETTINGS to R.id.tab_settings)
    val theme = Themes.current(this)
    val p = theme.palette(this)
    for ((tab, id) in tabs) {
        val view = findViewById<TextView>(id)
        // Which tab gets the box (the focus color, dimmed text) depends on the theme
        // (shapes.active_tab): in Pixel the open tab is plain bar and the other boxed, in a theme
        // whose box is brighter than its bar (Terminal) the open one is boxed (owner's choices)
        val isActive = tab == active
        val boxed = isActive == (theme.shapes.activeTab == "box")
        view.setTextColor(if (!boxed) p.barText else (p.text and 0x00FFFFFF) or (0x99 shl 24))
        view.setBackgroundColor(if (!boxed) Color.TRANSPARENT else p.focus)
        view.setOnClickListener(if (isActive) null else { _ -> openOther() })
    }
    showSettingsDot()
}

// "Settings ●" while the server has admin messages not yet read (AdminMessages)
fun Activity.showSettingsDot() {
    findViewById<TextView>(R.id.tab_settings).text = AdminMessages.withDot(this, getString(R.string.tab_settings))
}
