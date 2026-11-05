package com.q43b.tgflip

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

// Keys as the flip phone sends them (from its key layout file, matrix_keypad.kl):
// OK = DPAD_CENTER, soft keys = F1 / F2. Other phones may differ, so accept the alternatives.

// The left soft key. Phones report it either as F1 or as SOFT_LEFT.
fun KeyEvent.isLeftSoftKey() =
    keyCode == KeyEvent.KEYCODE_F1 || keyCode == KeyEvent.KEYCODE_SOFT_LEFT

// The right soft key. Phones report it either as F2 or as SOFT_RIGHT.
fun KeyEvent.isRightSoftKey() =
    keyCode == KeyEvent.KEYCODE_F2 || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT

// The OK / select key
fun KeyEvent.isOkKey() =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

// The soft key labels from soft_key_bar.xml: left above F1, right above F2.
// A label shows only while its key does something (text != null); the bar hides when
// neither does. Tapping a label does the same as its key, for touchscreens.
class SoftKeyBar(activity: Activity) {
    private val bar: View = activity.findViewById(R.id.soft_key_bar)
    private val left: TextView = activity.findViewById(R.id.soft_key_left)
    private val right: TextView = activity.findViewById(R.id.soft_key_right)

    fun onLeftTap(action: () -> Unit) = left.setOnClickListener { action() }

    fun onRightTap(action: () -> Unit) = right.setOnClickListener { action() }

    fun setLeft(text: CharSequence?) = set(left, text)

    fun setRight(text: CharSequence?) = set(right, text)

    private fun set(label: TextView, text: CharSequence?) {
        label.text = text ?: ""
        label.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
        val anyShown = left.visibility == View.VISIBLE || right.visibility == View.VISIBLE
        bar.visibility = if (anyShown) View.VISIBLE else View.GONE
    }
}
