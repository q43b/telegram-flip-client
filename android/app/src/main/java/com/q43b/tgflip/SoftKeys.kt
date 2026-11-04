package com.q43b.tgflip

import android.view.KeyEvent

// Keys as the flip phone sends them (from its key layout file, matrix_keypad.kl):
// OK = DPAD_CENTER, soft keys = F1 / F2. Other phones may differ, so accept the alternatives.

// The right soft key. Phones report it either as F2 or as SOFT_RIGHT.
fun KeyEvent.isRightSoftKey() =
    keyCode == KeyEvent.KEYCODE_F2 || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT

// The OK / select key
fun KeyEvent.isOkKey() =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
