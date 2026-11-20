package com.rusty.hearth

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Every app on the TV in a 4-wide grid, A–Z, with hidden apps dimmed in their
 * own section at the bottom. Hold OK (or press Menu) on a tile to add it to
 * or remove it from the home row, or hide it.
 */
class AllAppsActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private var lastFocus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = Store(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(ui.u(120), ui.u(64), ui.u(96), ui.u(96))
        }
        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        val apps = Apps.load(this)
        val favorites = store.favorites(apps)
        val hidden = store.hidden()
        list.removeAllViews()

        list.addView(ui.text(64, Ui.WHITE, Ui.LIGHT).apply { text = "All apps" })
        list.addView(ui.text(28, Ui.MUTED, Ui.LIGHT).apply {
            text = "Hold OK on an app to add it to the home screen or hide it"
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(12); bottomMargin = ui.u(24) })

        list.addView(grid(apps.filter { it.key !in hidden }, favorites, hidden))
        val hiddenApps = apps.filter { it.key in hidden }
        if (hiddenApps.isNotEmpty()) {
            list.addView(ui.text(26, Ui.MUTED, Ui.MEDIUM).apply { text = "HIDDEN"; letterSpacing = 0.18f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(32) })
            list.addView(grid(hiddenApps, favorites, hidden))
        }

        val target = lastFocus?.let { list.findViewWithTag<View>(it) } ?: list.findViewWithTag<View>(apps.firstOrNull()?.key)
        target?.post { target.requestFocus() }
    }

    private fun grid(apps: List<App>, favorites: List<String>, hidden: Set<String>) = GridLayout(this).apply {
        columnCount = 4
        clipChildren = false
        clipToPadding = false
        setPadding(0, ui.u(24), 0, 0)
        for (app in apps) {
            val caption = if (app.key in favorites) "★  ${app.label}" else app.label
            val tile = Tile(ui, 384, 216, caption, banner = app.banner,
                icon = app.icon.takeIf { app.banner == null }, title = app.label.takeIf { app.banner == null })
            if (app.key in hidden) tile.card.alpha = 0.45f
            tile.tag = app.key
            tile.setOnClickListener {
                runCatching { startActivity(app.intent) }
                    .onFailure { Toast.makeText(this@AllAppsActivity, "Couldn't open that", Toast.LENGTH_SHORT).show() }
            }
            tile.setOnLongClickListener { menu(app); true }
            val inner = tile.onFocusChangeListener
            tile.setOnFocusChangeListener { v, has -> inner?.onFocusChange(v, has); if (has) lastFocus = app.key }
            addView(tile, GridLayout.LayoutParams().apply {
                rightMargin = ui.u(48)
                bottomMargin = ui.u(36)
            })
        }
    }

    private fun menu(app: App) {
        val favorites = store.favorites(Apps.load(this))
        val hidden = store.hidden()
        val onHome = app.key in favorites
        val isHidden = app.key in hidden
        val options = arrayOf(
            if (onHome) "Remove from home" else "Add to home",
            if (isHidden) "Unhide" else "Hide",
            "App info",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (onHome) favorites.remove(app.key) else favorites.add(app.key)
                        store.saveFavorites(favorites)
                    }
                    1 -> {
                        if (isHidden) hidden.remove(app.key) else { hidden.add(app.key); favorites.remove(app.key) }
                        store.saveHidden(hidden)
                        store.saveFavorites(favorites)
                    }
                    2 -> startActivity(Apps.appInfo(app.pkg))
                }
                if (which != 2) rebuild()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus as? Tile)?.performLongClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
