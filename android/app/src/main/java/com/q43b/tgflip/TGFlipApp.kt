package com.q43b.tgflip

import android.app.Application

// Runs once when the app process starts, before any screen: applies the saved theme,
// so the first screen already opens light or dark.
class TGFlipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeSetting.apply(this)
    }
}
