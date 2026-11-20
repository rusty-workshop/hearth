package com.rusty.hearth

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Everything is designed on a 1920x1080 canvas; [u] scales a design pixel to
 * the real screen (the BRAVIA's UI runs at 1920x1080, so it's 1:1 there).
 */
class Ui(val context: Context) {
    private val k = context.resources.displayMetrics.widthPixels / 1920f

    fun u(n: Int) = (n * k).roundToInt()
    fun u(n: Float) = n * k

    fun text(size: Int, color: Int = WHITE, face: Typeface = REGULAR) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size.toFloat()))
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        // Keeps text readable over bright background photos.
        setShadowLayer(u(10f), 0f, u(2f), Color.parseColor("#B3000000"))
    }

    companion object {
        val WHITE = Color.parseColor("#F4F4F7")
        val SOFT = Color.parseColor("#C9CAD6")
        val MUTED = Color.parseColor("#8A8C9C")
        val ACCENT = Color.parseColor("#AA5CC3")
        val ACCENT_LIGHT = Color.parseColor("#C98BDB")
        // Slightly see-through so the background photo shows through glyph tiles.
        val CARD = Color.parseColor("#D91F2438")
        val GREEN = Color.parseColor("#4ADE80")
        val AMBER = Color.parseColor("#FBBF24")
        val THIN: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
