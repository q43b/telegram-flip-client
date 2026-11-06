package com.q43b.tgflip

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

// Black everywhere except a round hole in the middle: laid over a square video message so it
// looks round, as in Telegram. (The video itself draws on its own surface, which views can't
// clip to a circle, hence a mask on top instead.)
class RoundMaskDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }

    override fun draw(canvas: Canvas) {
        val b = bounds
        path.reset()
        path.addRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), Path.Direction.CW)
        path.addCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
