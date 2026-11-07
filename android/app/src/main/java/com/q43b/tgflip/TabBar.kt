package com.q43b.tgflip

import android.app.Activity
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.ContextCompat

enum class Tab { CHATS, SETTINGS }

// Highlights the active tab in tab_bar.xml. The d-pad switches tabs (Right = Settings,
// Left = back to Chats); tapping the other tab does the same on a touchscreen.
fun Activity.setUpTabBar(active: Tab, openOther: () -> Unit) {
    val tabs = mapOf(Tab.CHATS to R.id.tab_chats, Tab.SETTINGS to R.id.tab_settings)
    for ((tab, id) in tabs) {
        val view = findViewById<TextView>(id)
        val isActive = tab == active
        view.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        view.setTextColor(ContextCompat.getColor(this, if (isActive) R.color.white else R.color.tab_inactive))
        view.setBackgroundResource(if (isActive) R.color.tab_active else android.R.color.transparent)
        view.setOnClickListener(if (isActive) null else { _ -> openOther() })
    }
    showSettingsDot()
}

// "Settings ●" while the server has admin messages not yet read (AdminMessages)
fun Activity.showSettingsDot() {
    findViewById<TextView>(R.id.tab_settings).text = AdminMessages.withDot(this, getString(R.string.tab_settings))
}
