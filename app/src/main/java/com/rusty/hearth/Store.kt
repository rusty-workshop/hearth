package com.rusty.hearth

import android.content.Context

/**
 * What the family has customised: the order of apps on the home row, and
 * which apps are hidden from All apps. Stored on the TV, keyed by component.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hearth", Context.MODE_PRIVATE)

    /** Home row keys in order; seeded from [Apps.DEFAULT_FAVORITES] the first time. */
    fun favorites(installed: List<App>): MutableList<String> {
        val saved = prefs.getString(FAV, null)
        val keys = if (saved != null) saved.split('\n').filter { it.isNotEmpty() }
        else Apps.DEFAULT_FAVORITES.mapNotNull { pkg -> installed.firstOrNull { it.pkg == pkg }?.key }
        return keys.toMutableList()
    }

    fun saveFavorites(keys: List<String>) = prefs.edit().putString(FAV, keys.joinToString("\n")).apply()

    fun hidden(): MutableSet<String> = prefs.getStringSet(HIDDEN, emptySet())!!.toMutableSet()

    fun saveHidden(keys: Set<String>) = prefs.edit().putStringSet(HIDDEN, keys).apply()

    private companion object {
        const val FAV = "favorites"
        const val HIDDEN = "hidden"
    }
}
