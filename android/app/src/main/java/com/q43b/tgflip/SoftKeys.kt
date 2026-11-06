package com.q43b.tgflip

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

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

// The soft key labels: left above F1, right above F2. A label shows only while its key
// does something (text != null). They go to the phone's own soft key boxes where it has
// them (SystemSoftKeys), and to the app's own bar (soft_key_bar.xml) unless Settings says
// the phone shows them itself (SoftKeySetting). The app's bar hides when neither label is
// set. Tapping a label does the same as its key, for touchscreens.
class SoftKeyBar(private val activity: AppCompatActivity) {
    private val system = SystemSoftKeys(activity.window)
    private val bar: View = activity.findViewById(R.id.soft_key_bar)
    private val left: TextView = activity.findViewById(R.id.soft_key_left)
    private val right: TextView = activity.findViewById(R.id.soft_key_right)

    init {
        // The setting may have changed in Settings while this screen was behind it
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = updateBar()
        })
    }

    fun onLeftTap(action: () -> Unit) = left.setOnClickListener { action() }

    fun onRightTap(action: () -> Unit) = right.setOnClickListener { action() }

    fun setLeft(text: CharSequence?) = set(left, SystemSoftKeys.LEFT, text)

    fun setRight(text: CharSequence?) = set(right, SystemSoftKeys.RIGHT, text)

    private fun set(label: TextView, index: Int, text: CharSequence?) {
        system.set(index, text)
        label.text = text ?: ""
        label.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
        updateBar()
    }

    private fun updateBar() {
        val anyShown = left.visibility == View.VISIBLE || right.visibility == View.VISIBLE
        val shown = anyShown && !SoftKeySetting.isOn(activity)
        bar.visibility = if (shown) View.VISIBLE else View.GONE
    }
}

// The phone's own soft key labels: the gray boxes at the bottom of the screen on Japanese
// Android flip phones (NextFP platform, e.g. Kyocera's), through the system library's
// NfpSoftkeyGuide (manifest: <uses-library com.nextfp.android.util>). Reached by
// reflection, since the library only exists on those phones; elsewhere this does nothing.
// Index 0 is the center (OK) key, which we leave to the system.
class SystemSoftKeys(window: Window) {
    private val guide: Any? = if (isAvailable()) {
        try {
            guideClass().getMethod("getSoftkeyGuide", Window::class.java).invoke(null, window)
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "No soft key guide: $e")
            null
        }
    } else {
        null
    }
    private val setText = guide?.javaClass?.getMethod("setText", Int::class.javaPrimitiveType, CharSequence::class.java)
    private val invalidate = guide?.javaClass?.getMethod("invalidate")

    // An empty text leaves the box blank
    fun set(index: Int, text: CharSequence?) {
        val guide = guide ?: return
        try {
            setText?.invoke(guide, index, text ?: "")
            invalidate?.invoke(guide)
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "Couldn't set soft key $index: $e")
        }
    }

    companion object {
        private const val TAG = "SoftKeys"
        const val LEFT = 1
        const val RIGHT = 2

        private fun guideClass() = Class.forName("com.nextfp.android.util.NfpSoftkeyGuide")

        // true on phones with the system soft key boxes
        fun isAvailable() = try {
            guideClass()
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}

// Settings > "Running on a supported feature phone": On hides the app's own soft key bar,
// since the phone shows the labels itself. Defaults to whether such a phone was detected;
// can be switched either way (a wrong detection, or to look at the app's bar again).
object SoftKeySetting {
    private const val KEY = "system_soft_keys"

    fun isOn(context: Context) = prefs(context).getBoolean(KEY, SystemSoftKeys.isAvailable())

    fun save(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
