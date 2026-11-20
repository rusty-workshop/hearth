package com.rusty.hearth

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes Hearth the real home screen on Google TV. Sony's system launcher
 * (launcherx) outranks any app's "preferred home", so like Projectivy we:
 *
 *  - catch the remote's Home key before the system does and open Hearth, and
 *  - jump to Hearth whenever launcherx's home screen comes to the front some
 *    other way (e.g. backing out of an app).
 *
 * Enabled over adb by adding it to `enabled_accessibility_services`.
 */
class HomeKeyService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == LAUNCHERX &&
            event.className?.toString()?.endsWith(".home.HomeActivity") == true
        ) goHome()
    }

    override fun onInterrupt() {}

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private companion object {
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
    }
}
