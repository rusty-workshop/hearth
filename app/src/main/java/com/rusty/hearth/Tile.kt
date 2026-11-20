package com.rusty.hearth

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * A big 16:9 card with a caption under it. Shows an app banner if there is
 * one, otherwise a glyph or icon with a title on a dark card. Grows and gets
 * a white ring when focused; [moving] gives it a purple ring instead.
 */
@SuppressLint("ViewConstructor")
class Tile(
    private val ui: Ui,
    w: Int,
    h: Int,
    caption: String,
    banner: Drawable? = null,
    icon: Drawable? = null,
    glyph: String? = null,
    title: String? = null,
    subtitle: String? = null,
) : LinearLayout(ui.context) {

    val card = FrameLayout(context)
    private val captionView = ui.text(26, Ui.MUTED, Ui.REGULAR)
    private val ring = GradientDrawable().apply { cornerRadius = ui.u(20f) }

    var moving = false
        set(value) { field = value; restyle() }

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false

        card.background = GradientDrawable().apply { cornerRadius = ui.u(20f); setColor(Ui.CARD) }
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, ui.u(20f))
        }
        card.foreground = ring
        addView(card, LayoutParams(ui.u(w), ui.u(h)))

        if (banner != null) {
            card.addView(ImageView(context).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
            if (icon != null) col.addView(ImageView(context).apply { setImageDrawable(icon) },
                LayoutParams(ui.u(h / 2), ui.u(h / 2)))
            if (glyph != null) col.addView(ui.text(h / 3, Ui.ACCENT_LIGHT, Ui.LIGHT).apply { text = glyph })
            if (title != null) col.addView(ui.text(34, Ui.WHITE, Ui.MEDIUM).apply {
                text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(ui.u(16), ui.u(10), ui.u(16), 0)
            })
            if (subtitle != null) col.addView(ui.text(24, Ui.SOFT, Ui.LIGHT).apply {
                text = subtitle; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(ui.u(16), ui.u(8), ui.u(16), 0)
            })
            // A vertical LinearLayout stretches children full width, so center their text instead.
            for (i in 0 until col.childCount) (col.getChildAt(i) as? android.widget.TextView)?.gravity = Gravity.CENTER_HORIZONTAL
            card.addView(col, FrameLayout.LayoutParams(-1, -1))
        }

        captionView.text = caption
        captionView.maxLines = 1
        captionView.ellipsize = TextUtils.TruncateAt.END
        captionView.gravity = Gravity.CENTER_HORIZONTAL
        addView(captionView, LayoutParams(ui.u(w), -2).apply { topMargin = ui.u(22) })

        setOnFocusChangeListener { _, _ -> restyle() }
        restyle()
    }

    private fun restyle() {
        val focused = hasFocus()
        val grow = if (focused || moving) 1.1f else 1f
        card.pivotX = card.layoutParams.width / 2f
        card.pivotY = card.layoutParams.height / 2f
        card.animate().scaleX(grow).scaleY(grow).setDuration(140).start()
        card.elevation = if (focused) ui.u(24f) else 0f
        ring.setStroke(ui.u(5), when {
            moving -> Ui.ACCENT
            focused -> Ui.WHITE
            else -> Color.TRANSPARENT
        })
        captionView.setTextColor(if (focused || moving) Ui.WHITE else Ui.MUTED)
        captionView.animate().translationY(if (focused || moving) ui.u(10f) else 0f).setDuration(140).start()
    }
}
