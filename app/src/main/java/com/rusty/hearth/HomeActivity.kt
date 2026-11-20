package com.rusty.hearth

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The family room home screen: clock and weather up top, a row of big app
 * tiles (the family's favorites, reorderable), and a row of HDMI inputs plus
 * All apps and Settings.
 *
 * Hold OK (or press Menu) on an app tile to move it or take it off the row.
 * While moving, left/right shifts it and OK or Back drops it.
 */
class HomeActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var favRow: LinearLayout
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var moreRow: LinearLayout
    private lateinit var weatherBig: TextView
    private lateinit var weatherLine: TextView
    private lateinit var countdownLine: TextView

    private var apps: List<App> = emptyList()
    private var favorites = mutableListOf<String>()
    private var movingKey: String? = null
    private var lastFocus: String? = null
    private var resumed = false

    // background photos
    private lateinit var photos: Photos
    private val photoWorker = Executors.newSingleThreadExecutor()
    private val photoLayers by lazy { listOf(ImageView(this), ImageView(this)) }
    private var front = 0
    private lateinit var credit: TextView
    private lateinit var saveChip: TextView
    private lateinit var onlyChip: TextView
    private lateinit var nightShade: View
    private lateinit var greeting: TextView
    private var current: Photo? = null
    private var photoShownAt = 0L
    private var photoGen = 0  // bumped on each resume so stale callbacks drop out

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (resumed) rebuild() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = Store(this)
        photos = Photos.get(this)
        setContentView(layout())
        // Start with last time's photo so the screen isn't bare while a new one downloads.
        photoWorker.execute { photos.last()?.let { p -> main.post { if (photoShownAt == 0L) showPhoto(p, fade = false) } } }
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        rebuild()
        refreshFeed()
        tickTimeOfDay()
        val gen = ++photoGen
        val wait = PHOTO_MS - (System.currentTimeMillis() - photoShownAt)
        if (photoShownAt == 0L || wait <= 0) nextPhoto(gen) else main.postDelayed({ nextPhoto(gen) }, wait)
    }

    override fun onPause() {
        resumed = false
        endMove()
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        worker.shutdownNow()
        photoWorker.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while already home: jump back to the first tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        endMove()
        favScroll.smoothScrollTo(0, 0)
        (favRow.getChildAt(0) ?: moreRow.getChildAt(0))?.requestFocus()
    }

    // ---- layout -------------------------------------------------------------

    /** Photo layers at the back, a darkening gradient, then the screen itself, then the photo credit. */
    private fun layout(): View {
        val frame = FrameLayout(this)
        for (layer in photoLayers) {
            layer.scaleType = ImageView.ScaleType.CENTER_CROP
            layer.alpha = 0f
            frame.addView(layer, FrameLayout.LayoutParams(-1, -1))
        }
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.parseColor("#CC0C0E14"), Color.parseColor("#660C0E14"),
                Color.parseColor("#A60C0E14"), Color.parseColor("#F20C0E14")))
        }, FrameLayout.LayoutParams(-1, -1))
        // Extra darkening at night (see tickTimeOfDay).
        nightShade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        frame.addView(nightShade, FrameLayout.LayoutParams(-1, -1))
        frame.addView(content(), FrameLayout.LayoutParams(-1, -1))
        frame.addView(photoBar(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = ui.u(48); bottomMargin = ui.u(18)
        })
        return frame
    }

    /** Bottom-right: the photo credit, then Next / Save / Hide (press Down from the inputs row). */
    private fun photoBar(): View {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        credit = ui.text(22, Color.parseColor("#99FFFFFF"), Ui.LIGHT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = ui.u(820)
        }
        bar.addView(credit)
        fun add(chip: TextView) = bar.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.u(14) })
        add(chip("Next photo") { nextPhotoNow() })
        saveChip = chip("♡ Save") { photoAction { photos.toggleSaved(it) } }
        add(saveChip)
        add(chip("Hide photo") { _ -> photoAction { photos.hide(it) }; nextPhotoNow() })
        onlyChip = chip("Saved only") { photoAction { photos.savedOnly = !photos.savedOnly }; nextPhotoNow() }
        add(onlyChip)
        updatePhotoBar()
        return bar
    }

    private fun chip(label: String, onClick: (View) -> Unit) = ui.text(24, Ui.SOFT, Ui.MEDIUM).apply {
        text = label
        isFocusable = true
        isClickable = true
        setPadding(ui.u(22), ui.u(10), ui.u(22), ui.u(10))
        val bg = GradientDrawable().apply { cornerRadius = ui.u(40f) }
        background = bg
        fun style(focused: Boolean) {
            bg.setColor(if (focused) Ui.WHITE else Color.parseColor("#66000000"))
            setTextColor(if (focused) Color.parseColor("#14161F") else Ui.SOFT)
            setShadowLayer(if (focused) 0f else ui.u(8f), 0f, ui.u(2f), Color.parseColor("#B3000000"))
        }
        style(false)
        setOnFocusChangeListener { _, f -> style(f) }
        setOnClickListener(onClick)
    }

    private fun content(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, ui.u(48), 0, 0)
        }

        // Header: clock and date on the left, weather on the right.
        val header = LinearLayout(this).apply { setPadding(ui.u(96), 0, ui.u(96), 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextClock(this).apply {
            setShadowLayer(ui.u(14f), 0f, ui.u(2f), Color.parseColor("#B3000000"))
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.u(150f))
            setTextColor(Ui.WHITE); typeface = Ui.THIN; includeFontPadding = false
        })
        left.addView(TextClock(this).apply {
            setShadowLayer(ui.u(10f), 0f, ui.u(2f), Color.parseColor("#B3000000"))
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.u(40f))
            setTextColor(Ui.SOFT); typeface = Ui.LIGHT; includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(14) })
        greeting = ui.text(30, Ui.ACCENT_LIGHT, Ui.REGULAR)
        left.addView(greeting, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(14) })
        header.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; alpha = 0f }
        weatherBig = ui.text(96, Ui.WHITE, Ui.LIGHT)
        weatherLine = ui.text(32, Ui.SOFT, Ui.LIGHT)
        countdownLine = ui.text(30, Ui.ACCENT_LIGHT, Ui.REGULAR)
        right.addView(weatherBig)
        right.addView(weatherLine, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(12) })
        right.addView(countdownLine, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(12) })
        header.addView(right, LinearLayout.LayoutParams(-2, -2))
        root.addView(header)

        root.addView(eyebrow("WATCH"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(26) })
        favRow = row()
        favScroll = scroller(favRow)
        root.addView(favScroll)

        root.addView(eyebrow("INPUTS & MORE"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(8) })
        moreRow = row()
        root.addView(scroller(moreRow))
        return root
    }

    private fun eyebrow(label: String) = ui.text(26, Ui.MUTED, Ui.MEDIUM).apply {
        text = label
        letterSpacing = 0.18f
        setPadding(ui.u(96), 0, 0, 0)
    }

    private fun row() = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }

    private fun scroller(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFillViewport = true
        setPadding(ui.u(96), ui.u(30), ui.u(96), ui.u(30))
        addView(row)
    }

    // ---- rows ---------------------------------------------------------------

    private fun rebuild() {
        apps = Apps.load(this)
        val byKey = apps.associateBy { it.key }
        favorites = store.favorites(apps).filter { it in byKey }.toMutableList()

        favRow.removeAllViews()
        for (key in favorites) {
            val app = byKey.getValue(key)
            add(favRow, Tile(ui, 384, 216, app.label, banner = app.banner,
                icon = app.icon.takeIf { app.banner == null }, title = app.label.takeIf { app.banner == null }),
                "app:$key", onClick = { launch(app.intent) }, onMenu = { appMenu(app) })
        }
        if (favorites.isEmpty()) {
            add(favRow, Tile(ui, 384, 216, "Add apps", glyph = "+"), "add",
                onClick = { startActivity(Intent(this, AllAppsActivity::class.java)) })
        }

        moreRow.removeAllViews()
        for (input in Apps.inputs(this)) {
            val sub = when {
                input.device != null -> input.device
                input.state == TvInputManager.INPUT_STATE_DISCONNECTED -> "Nothing plugged in"
                input.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Standby"
                else -> "Connected"
            }
            val tile = Tile(ui, 288, 162, input.label, glyph = "⎆", subtitle = sub)
            if (input.state == TvInputManager.INPUT_STATE_DISCONNECTED) tile.card.alpha = 0.5f
            add(moreRow, tile, "input:${input.id}", onClick = { launch(input.intent) })
        }
        add(moreRow, Tile(ui, 288, 162, "All apps", glyph = "▦"), "all",
            onClick = { startActivity(Intent(this, AllAppsActivity::class.java)) })
        add(moreRow, Tile(ui, 288, 162, "Settings", glyph = "⚙"), "settings",
            onClick = { launch(Intent(Settings.ACTION_SETTINGS)) })

        restoreFocus()
    }

    private fun add(row: LinearLayout, tile: Tile, key: String, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
        tile.tag = key
        tile.setOnClickListener { onClick() }
        if (onMenu != null) tile.setOnLongClickListener { onMenu(); true }
        tile.onFocusChangeListener.let { inner ->
            tile.setOnFocusChangeListener { v, has -> inner?.onFocusChange(v, has); if (has) lastFocus = key }
        }
        if (key == "app:$movingKey") tile.moving = true
        row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply {
            if (row.childCount > 0) leftMargin = ui.u(if (row === favRow) 44 else 36)
        })
    }

    private fun restoreFocus() {
        val want = lastFocus
        val target = want?.let { favRow.findViewWithTag<View>(it) ?: moreRow.findViewWithTag(it) }
            ?: favRow.getChildAt(0) ?: moreRow.getChildAt(0)
        target?.post { target.requestFocus() }
    }

    private fun launch(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show() }
    }

    // ---- reordering ---------------------------------------------------------

    private fun appMenu(app: App) {
        val options = arrayOf("Move", "Remove from home", "App info")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startMove(app.key)
                    1 -> { favorites.remove(app.key); store.saveFavorites(favorites); lastFocus = null; rebuild() }
                    2 -> launch(Apps.appInfo(app.pkg))
                }
            }
            .show()
    }

    private fun startMove(key: String) {
        movingKey = key
        (favRow.findViewWithTag<View>("app:$key") as? Tile)?.moving = true
    }

    private fun endMove() {
        val key = movingKey ?: return
        movingKey = null
        (favRow.findViewWithTag<View>("app:$key") as? Tile)?.moving = false
        store.saveFavorites(favorites)
    }

    private fun shift(by: Int) {
        val key = movingKey ?: return
        val from = favorites.indexOf(key)
        val to = from + by
        if (from < 0 || to !in favorites.indices) return
        favorites.add(to, favorites.removeAt(from))
        store.saveFavorites(favorites)
        lastFocus = "app:$key"
        rebuild()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingKey == null) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                currentFocus?.let { if (it is Tile && (it.tag as? String)?.startsWith("app:") == true) it.performLongClick() }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in SKIP_KEYS) { nextPhotoNow(); return true }
            // Back on the home screen has nowhere to go.
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> shift(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> shift(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> endMove()
        }
        return true
    }

    // ---- weather ------------------------------------------------------------

    private fun refreshFeed() {
        worker.execute {
            val feed = Feed.fetch()
            main.post {
                if (feed != null) showFeed(feed)  // otherwise keep the last good one
                if (resumed) main.postDelayed({ refreshFeed() }, FEED_MS)
            }
        }
    }

    private fun showFeed(f: Feed) {
        weatherBig.text = "${f.icon} ${f.temp}°"
        weatherLine.text = "${f.summary}  ·  H ${f.high}°  L ${f.low}°"
        countdownLine.text = f.countdown?.let { (name, days) ->
            when (days) { 0 -> "$name is today!"; 1 -> "$name is tomorrow"; else -> "$name in $days days" }
        }.orEmpty()
        countdownLine.visibility = if (f.countdown == null) View.GONE else View.VISIBLE
        (weatherBig.parent as View).animate().alpha(1f).setDuration(400).start()
    }

    // ---- background photos --------------------------------------------------

    private fun nextPhoto(gen: Int) {
        if (gen != photoGen) return
        photoWorker.execute {
            val photo = photos.next()
            main.post {
                if (gen != photoGen || !resumed) return@post
                if (photo != null) showPhoto(photo, fade = true)
                // On failure, try again sooner than a full rotation.
                main.postDelayed({ nextPhoto(gen) }, if (photo != null) PHOTO_MS else RETRY_MS)
            }
        }
    }

    private fun showPhoto(photo: Photo, fade: Boolean) {
        val old = photoLayers[front]
        front = 1 - front
        val new = photoLayers[front]
        new.setImageBitmap(photo.bitmap)
        // The two layers never change order, so whichever is on top does the fading.
        val frame = new.parent as FrameLayout
        val newOnTop = frame.indexOfChild(new) > frame.indexOfChild(old)
        if (fade && newOnTop) {
            new.alpha = 0f
            new.animate().alpha(1f).setDuration(1500).withEndAction { old.alpha = 0f; old.setImageDrawable(null) }.start()
        } else if (fade) {
            new.alpha = 1f
            old.animate().alpha(0f).setDuration(1500).withEndAction { old.setImageDrawable(null) }.start()
        } else {
            new.alpha = 1f
            old.alpha = 0f
        }
        credit.text = photo.credit
        current = photo
        photoShownAt = System.currentTimeMillis()
        updatePhotoBar()
    }

    /** Skip to a new photo now and restart the 5-minute timer. */
    private fun nextPhotoNow() {
        val gen = ++photoGen
        nextPhoto(gen)
    }

    /** Save/hide/toggle on the current photo (quick SharedPreferences writes), then refresh the buttons. */
    private fun photoAction(action: (Photo) -> Unit) {
        val photo = current ?: return
        action(photo)
        updatePhotoBar()
    }

    private fun updatePhotoBar() {
        val photo = current
        saveChip.text = if (photo != null && photos.isSaved(photo.title)) "★ Saved" else "♡ Save"
        // "Saved only" appears once there are a few to rotate through.
        onlyChip.visibility = if (photos.savedCount() >= 3 || photos.savedOnly) View.VISIBLE else View.GONE
        onlyChip.text = if (photos.savedOnly) "Saved only: on" else "Saved only: off"
    }

    // ---- time of day --------------------------------------------------------

    private fun tickTimeOfDay() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greeting.text = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
        // From 9pm to 6am: a dimmer room-friendly background (and night photos, see Photos).
        val target = if (Photos.isNight()) 0.35f else 0f
        if (nightShade.alpha != target) nightShade.animate().alpha(target).setDuration(3000).start()
        if (resumed) main.postDelayed({ tickTimeOfDay() }, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    private companion object {
        const val FEED_MS = 10 * 60_000L
        const val PHOTO_MS = 5 * 60_000L
        const val RETRY_MS = 120_000L
        val SKIP_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}
