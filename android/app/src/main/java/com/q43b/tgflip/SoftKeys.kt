package com.q43b.tgflip

import android.view.KeyEvent

// The right soft key. Phones report it either as F2 or as SOFT_RIGHT.
fun KeyEvent.isRightSoftKey() =
    keyCode == KeyEvent.KEYCODE_F2 || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT
