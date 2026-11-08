package com.q43b.tgflip

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

// Paints every screen from the current theme (docs/themes-plan.md). Installed once by TGFlipApp:
// each time a screen starts it walks the screen's views; lists style their rows as they add them.
// Views say what they are with android:tag="theme:<role>":
//   title    a screen's title line: bar colors, title size
//   bar      a bar (soft keys, tabs): bar colors, small size, and its labels too
//   message  message text and the message box: the theme's text font
//   mono     keeps its own font (the log), only scaled
//   secondary  quieter text (text_secondary)
//   overlay  text over a photo or video: keeps its colors (white on black)
//   connection, tab  font and size only: their code colors them by state
// Everything else: the frame font, the text color, its own size times the UI size.
object Themer {
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        // Every start, so a UI size changed in Settings reaches the screens behind it too
        override fun onActivityStarted(activity: Activity) = apply(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })

    fun apply(activity: Activity) {
        val p = Themes.current(activity).palette(activity)
        activity.window.setBackgroundDrawable(ColorDrawable(p.background))
        activity.window.statusBarColor = p.bar
        style(activity.findViewById(android.R.id.content), inRow = false)
    }

    // A pop-up from MessageMenu (more dialogs in step 2): the panel, its title, list and buttons
    fun apply(dialog: AlertDialog) {
        val context = dialog.context
        val theme = Themes.current(context)
        val p = theme.palette(context)
        val inset = px(context, 16f)
        dialog.window?.setBackgroundDrawable(InsetDrawable(Panel(context, theme, p), inset, inset, inset, inset))
        dialog.window?.decorView?.let { style(it, inRow = false) }
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.let {
            it.typeface = theme.frameFont
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.sizes.titleSp * Themes.scale(context))
        }
    }

    private fun style(view: View, inRow: Boolean) {
        val role = (view.tag as? String)?.removePrefix("theme:")
        when (view) {
            is AbsListView -> return styleList(view)  // it styles its own rows and cells
            is TextView -> styleText(view, role, inRow)
        }
        if (view is ViewGroup) {
            if (role == "bar" || role == "title") view.setBackgroundColor(palette(view).bar)
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                // A bar's labels are bar text, unless they say otherwise
                if (role == "bar" && child.tag == null) child.tag = "theme:bar"
                style(child, inRow)
            }
        }
    }

    private fun styleText(view: TextView, role: String?, inRow: Boolean) {
        val theme = Themes.current(view.context)
        val p = theme.palette(view.context)
        val scale = Themes.scale(view.context)
        if (role != "mono") view.typeface = if (role == "message" || view is EditText) theme.textFont else theme.frameFont
        if (view is CheckedTextView) view.checkMarkTintList = ColorStateList.valueOf(p.text)
        // Anything the d-pad can reach outside a list (the version line, dialog buttons) gets
        // the same focus frame as a list row, and nothing while not focused
        if (!inRow && view.isFocusable && view !is EditText && role !in BAR_ROLES) {
            view.background = whileFocused(Focus(view.context, theme, p))
        }
        when (role) {
            "title" -> {
                view.setBackgroundColor(p.bar)
                view.setTextColor(p.barText)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.sizes.titleSp * scale)
            }
            "bar" -> {
                view.setTextColor(p.barText)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.sizes.smallSp * scale)
            }
            // Their colors follow their state (ConnectionBar, TabBar take them from the palette)
            "connection", "tab" -> view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.sizes.smallSp * scale)
            else -> {
                // overlay: text on a photo or video (white on black) keeps its own colors
                if (role != "overlay") {
                    view.setTextColor(if (role == "secondary") p.textSecondary else p.text)
                    view.setHintTextColor(p.textSecondary)
                }
                if (view is EditText) view.backgroundTintList = ColorStateList.valueOf(p.text)
                if (inRow && role != "mono") {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.sizes.textSp * scale)
                } else {
                    // Its own size from the layout, remembered the first time, times the UI size
                    val original = view.getTag(R.id.theme_original_size) as? Float
                        ?: view.textSize.also { view.setTag(R.id.theme_original_size, it) }
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, original * scale)
                }
            }
        }
    }

    // For rows that draw their own divider: the theme's divider and its height in px, or null for none
    fun newDivider(context: Context): Pair<Drawable, Int>? {
        val theme = Themes.current(context)
        if (theme.shapes.divider == "none") return null
        val height = maxOf(1, px(context, theme.shapes.dividerDp))
        return Divider(theme.palette(context).divider, dashed = theme.shapes.divider == "dashed") to height
    }

    // A list row's top and bottom padding as styleRow sets it, in px
    fun rowPaddingPx(context: Context) = px(context, Themes.current(context).sizes.rowPaddingDp * Themes.scale(context))

    private val BAR_ROLES = setOf("title", "bar", "tab", "connection")

    // A drawable shown only in the focused state (and the extra states given), else nothing
    private fun whileFocused(drawable: Drawable, vararg alsoIn: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), drawable)
        for (state in alsoIn) addState(intArrayOf(state), drawable)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    // A list (rows) or a grid (the sticker picker's cells, which keep their own layout)
    private fun styleList(list: AbsListView) {
        val context = list.context
        val theme = Themes.current(context)
        val p = theme.palette(context)
        // Only while the list itself has focus (or is touched): when focus moves on, to the
        // version line or the message box, the list keeps its selected row but shows nothing
        list.selector = whileFocused(Focus(context, theme, p), android.R.attr.state_pressed)
        list.isDrawSelectorOnTop = false
        if (list is ListView && list.tag == "theme:own_divider") {
            // The rows draw their dividers themselves (chat rows: none between a message and its media)
            list.divider = null
            list.dividerHeight = 0
        } else if (list is ListView) {
            val divider = if (theme.shapes.divider == "none") 0 else maxOf(1, px(context, theme.shapes.dividerDp))
            list.divider = if (divider == 0) null else Divider(p.divider, dashed = theme.shapes.divider == "dashed")
            list.dividerHeight = divider
        }
        val styleChild: (View) -> Unit = if (list is ListView) ::styleRow else { cell -> style(cell, inRow = false) }
        // Rows are styled as the list adds them (recycled rows keep their style)
        list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) = styleChild(child)
            override fun onChildViewRemoved(parent: View, child: View) {}
        })
        for (i in 0 until list.childCount) styleChild(list.getChildAt(i))
    }

    // A list row: no minimum height (the UI size decides), the theme's padding, row text size
    private fun styleRow(row: View) {
        if (row.tag == "theme:mono") return style(row, inRow = true)  // keeps its own small layout
        val pad = px(row.context, Themes.current(row.context).sizes.rowPaddingDp * Themes.scale(row.context))
        row.minimumHeight = 0
        if (row is TextView) row.minHeight = 0
        row.setPaddingRelative(row.paddingStart, pad, row.paddingEnd, pad)
        style(row, inRow = true)
    }

    private fun palette(view: View) = Themes.current(view.context).palette(view.context)

    fun px(context: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()

    // The shape every theme box is drawn with: square, round or notched (pixel-art) corners
    private fun outline(theme: Theme, rect: RectF, corner: Float): Path = Path().apply {
        val (l, t, r, b) = listOf(rect.left, rect.top, rect.right, rect.bottom)
        when (theme.shapes.corners) {
            "round" -> addRoundRect(rect, corner * 3, corner * 3, Path.Direction.CW)
            "notch" -> {
                // The corner pixel left out: a stair step of one outline width at each corner
                val n = corner
                moveTo(l + n, t); lineTo(r - n, t); lineTo(r - n, t + n); lineTo(r, t + n)
                lineTo(r, b - n); lineTo(r - n, b - n); lineTo(r - n, b); lineTo(l + n, b)
                lineTo(l + n, b - n); lineTo(l, b - n); lineTo(l, t + n); lineTo(l + n, t + n); close()
            }
            else -> addRect(rect, Path.Direction.CW)
        }
    }

    // The focused row: fill, frame (filled and outlined) or marker (a ► at the start)
    private class Focus(context: Context, private val theme: Theme, private val p: Theme.Palette) : BaseDrawable() {
        private val line = px(context, theme.shapes.outlineDp * Themes.scale(context)).coerceAtLeast(1).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.text
            typeface = theme.frameFont
            textSize = px(context, theme.sizes.textSp * Themes.scale(context)).toFloat()  // as big as the row's text
        }

        override fun draw(canvas: Canvas) {
            val box = RectF(bounds).apply { inset(line, line / 2) }
            when (theme.shapes.focus) {
                "marker" -> canvas.drawText("►", line, bounds.exactCenterY() - (marker.ascent() + marker.descent()) / 2, marker)
                "frame" -> {
                    paint.color = p.focusOutline
                    canvas.drawPath(outline(theme, box, line), paint)
                    paint.color = p.focus
                    canvas.drawPath(outline(theme, RectF(box).apply { inset(line, line) }, line), paint)
                }
                else -> {
                    paint.color = p.focus
                    canvas.drawPath(outline(theme, box, line), paint)
                }
            }
        }
    }

    // A pop-up's panel: the background, outlined like the focus frame
    private class Panel(context: Context, private val theme: Theme, private val p: Theme.Palette) : BaseDrawable() {
        private val line = px(context, theme.shapes.outlineDp * Themes.scale(context)).coerceAtLeast(1).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val box = RectF(bounds)
            paint.color = p.focusOutline
            canvas.drawPath(outline(theme, box, line), paint)
            paint.color = p.background
            canvas.drawPath(outline(theme, RectF(box).apply { inset(line, line) }, line), paint)
        }
    }

    private class Divider(color: Int, private val dashed: Boolean) : BaseDrawable() {
        private val paint = Paint().apply { this.color = color }

        override fun draw(canvas: Canvas) {
            if (!dashed) {
                canvas.drawRect(bounds, paint)
                return
            }
            val h = bounds.height().toFloat()
            paint.strokeWidth = h
            paint.pathEffect = DashPathEffect(floatArrayOf(h * 4, h * 3), 0f)
            canvas.drawLine(bounds.left.toFloat(), bounds.exactCenterY(), bounds.right.toFloat(), bounds.exactCenterY(), paint)
        }
    }

    private abstract class BaseDrawable : Drawable() {
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
