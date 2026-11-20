package com.rusty.hearth

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Hearth's screensaver: a full-screen nature photo with a slow zoom, a big
 * clock, the date and the weather. The photo changes every minute, using the
 * same Wikimedia photos (and saved/hidden choices) as the home screen.
 *
 * To avoid burn-in, the clock swaps between the bottom-left and bottom-right
 * corners with each new photo. Any button wakes the TV.
 */
class HearthDream : DreamService() {

    private lateinit var ui: Ui
    private lateinit var photos: Photos
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val layers by lazy { listOf(ImageView(this), ImageView(this)) }
    private var front = 0
    private lateinit var info: LinearLayout
    private lateinit var weather: TextView
    private lateinit var credit: TextView
    private lateinit var nightShade: View
    private var onLeft = true
    private var running = false
    private var zoom: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        ui = Ui(this)
        photos = Photos.get(this)
        setContentView(layout())
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = true
        nightShade.alpha = if (Photos.isNight()) 0.3f else 0f
        worker.execute { photos.last()?.let { p -> main.post { if (running && current == null) show(p, fade = false) } } }
        nextPhoto()
        refreshWeather()
    }

    override fun onDreamingStopped() {
        running = false
        main.removeCallbacksAndMessages(null)
        zoom?.cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }

    private var current: Photo? = null

    private fun layout(): View {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.parseColor("#0C0E14"))
        for (layer in layers) {
            layer.scaleType = ImageView.ScaleType.CENTER_CROP
            layer.alpha = 0f
            frame.addView(layer, FrameLayout.LayoutParams(-1, -1))
        }
        // Darken the bottom third so the clock reads, and everything a little at night.
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#990C0E14"), Color.parseColor("#E60C0E14")))
        }, FrameLayout.LayoutParams(-1, -1))
        nightShade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        frame.addView(nightShade, FrameLayout.LayoutParams(-1, -1))

        info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.u(200f))
            setTextColor(Ui.WHITE); typeface = Ui.THIN; includeFontPadding = false
            setShadowLayer(ui.u(16f), 0f, ui.u(2f), Color.parseColor("#B3000000"))
        })
        info.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.u(44f))
            setTextColor(Ui.SOFT); typeface = Ui.LIGHT; includeFontPadding = false
            setShadowLayer(ui.u(10f), 0f, ui.u(2f), Color.parseColor("#B3000000"))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(10) })
        weather = ui.text(38, Ui.SOFT, Ui.LIGHT)
        info.addView(weather, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(16) })
        frame.addView(info, infoParams())

        credit = ui.text(22, Color.parseColor("#80FFFFFF"), Ui.LIGHT).apply { maxLines = 1; maxWidth = ui.u(900) }
        frame.addView(credit, creditParams())
        return frame
    }

    private fun infoParams() = FrameLayout.LayoutParams(-2, -2,
        Gravity.BOTTOM or if (onLeft) Gravity.START else Gravity.END).apply {
        leftMargin = ui.u(96); rightMargin = ui.u(96); bottomMargin = ui.u(80)
    }

    private fun creditParams() = FrameLayout.LayoutParams(-2, -2,
        Gravity.BOTTOM or if (onLeft) Gravity.END else Gravity.START).apply {
        leftMargin = ui.u(48); rightMargin = ui.u(48); bottomMargin = ui.u(36)
    }

    private fun nextPhoto() {
        worker.execute {
            val photo = photos.next()
            main.post {
                if (!running) return@post
                if (photo != null) show(photo, fade = true)
                main.postDelayed({ nextPhoto() }, if (photo != null) PHOTO_MS else RETRY_MS)
            }
        }
    }

    private fun show(photo: Photo, fade: Boolean) {
        val old = layers[front]
        front = 1 - front
        val new = layers[front]
        new.setImageBitmap(photo.bitmap)
        new.scaleX = 1f; new.scaleY = 1f
        val parent = new.parent as FrameLayout
        if (!fade) { new.alpha = 1f; old.alpha = 0f }
        else if (parent.indexOfChild(new) > parent.indexOfChild(old)) {
            new.alpha = 0f
            new.animate().alpha(1f).setDuration(2000).withEndAction { old.alpha = 0f; old.setImageDrawable(null) }.start()
        } else {
            new.alpha = 1f
            old.animate().alpha(0f).setDuration(2000).withEndAction { old.setImageDrawable(null) }.start()
        }
        // A slow push-in over the minute the photo is up.
        zoom?.cancel()
        zoom = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = PHOTO_MS + 5_000
            interpolator = LinearInterpolator()
            addUpdateListener { new.scaleX = it.animatedValue as Float; new.scaleY = new.scaleX }
            start()
        }
        // Swap corners each photo so nothing sits in one place for hours.
        if (current != null) {
            onLeft = !onLeft
            info.animate().alpha(0f).setDuration(600).withEndAction {
                info.layoutParams = infoParams()
                credit.layoutParams = creditParams()
                info.animate().alpha(1f).setDuration(600).start()
            }.start()
        }
        credit.text = photo.credit
        current = photo
        nightShade.animate().alpha(if (Photos.isNight()) 0.3f else 0f).setDuration(2000).start()
    }

    private fun refreshWeather() {
        worker.execute {
            val f = Feed.fetch()
            main.post {
                if (!running) return@post
                if (f != null) weather.text = "${f.icon}  ${f.temp}°  ${f.summary}"
                main.postDelayed({ refreshWeather() }, 10 * 60_000L)
            }
        }
    }

    private companion object {
        const val PHOTO_MS = 60_000L
        const val RETRY_MS = 120_000L
    }
}
