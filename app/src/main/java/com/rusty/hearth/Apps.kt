package com.rusty.hearth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri

/** One launchable app. [key] is its flattened component name. */
class App(
    val key: String,
    val pkg: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    val intent: Intent,
)

/** One physical HDMI port, plus the name of whatever CEC device is plugged into it. */
class Input(val id: String, val label: String, val device: String?, val state: Int) {
    val intent: Intent get() = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
}

object Apps {

    /**
     * Apps that start on the home row the first time Hearth runs, in order, if installed.
     * Copied from Projectivy's "TV Apps" row on the Family Room TV (2026-11-19).
     */
    val DEFAULT_FAVORITES = listOf(
        "com.netflix.ninja",
        "com.disney.disneyplus",
        "com.google.android.youtube.tv",
        "org.jellyfin.androidtv",
        "com.rusty.lookout",
        "com.amazon.amazonvideo.livingroom",
        "com.sling",
        "org.smarttube.stable",
        "air.com.vudu.air.DownloaderTablet",
        "com.android.vending",
    )

    /** Every launchable app except Hearth itself, one entry per package, sorted by name. */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<App>()
        // TV (leanback) entries first so they win over phone-style LAUNCHER ones.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, PackageManager.MATCH_ALL)) {
                val ai = ri.activityInfo
                if (ai.packageName == context.packageName) continue
                // Projectivy's per-input shortcuts duplicate our own Inputs row.
                if (ai.name.contains(".guidedActions.")) continue
                if (!seen.add(ai.packageName)) continue
                val component = ComponentName(ai.packageName, ai.name)
                out += App(
                    key = component.flattenToString(),
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString().trim(),
                    banner = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER)
                        ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) else null,
                    icon = ai.loadIcon(pm),
                    intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** The TV's HDMI ports (not the CEC devices hanging off them), in port order. */
    fun inputs(context: Context): List<Input> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val all = runCatching { tim.tvInputList }.getOrDefault(emptyList())
        fun label(info: TvInputInfo) =
            (info.loadCustomLabel(context) ?: info.loadLabel(context))?.toString()?.trim().orEmpty()
        return all.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .map { port ->
                val device = all.firstOrNull { it.parentId == port.id }?.let(::label)?.takeIf { it.isNotEmpty() }
                Input(port.id, label(port).ifEmpty { "HDMI" }, device, tim.getInputState(port.id))
            }
            .sortedBy { it.label }
    }

    fun appInfo(pkg: String) = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", pkg, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
