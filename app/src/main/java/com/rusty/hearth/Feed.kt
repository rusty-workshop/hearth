package com.rusty.hearth

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** The bits of latitude-lookout's /api/feed the home screen shows. */
class Feed(
    val temp: Int,
    val summary: String,
    val high: Int,
    val low: Int,
    val icon: String,
    /** The soonest countdown, e.g. "Thanksgiving" in 8 days, or null. */
    val countdown: Pair<String, Int>?,
) {
    companion object {
        const val URL = "http://192.168.1.158:8473/api/feed"

        /** Blocking; call off the main thread. Returns null if the Latitude is unreachable. */
        fun fetch(): Feed? = runCatching {
            val conn = URL(URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val f = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val w = f.getJSONObject("weather")
            val c = f.optJSONArray("countdowns")?.optJSONObject(0)
            Feed(
                temp = w.getInt("temp"),
                summary = w.getString("summary"),
                high = w.getInt("high"),
                low = w.getInt("low"),
                icon = icon(w.getString("summary"), w.optString("sunrise"), w.optString("sunset")),
                countdown = c?.let { it.getString("name") to it.getInt("days") },
            )
        }.getOrNull()

        private fun minutes(hhmm: String): Int? =
            hhmm.takeLast(5).split(':').takeIf { it.size == 2 }?.let { (h, m) ->
                (h.toIntOrNull() ?: return null) * 60 + (m.toIntOrNull() ?: return null)
            }

        /** Same emoji mapping as Lookout's weather page. */
        private fun icon(summary: String, sunrise: String, sunset: String): String {
            val now = Calendar.getInstance().run { get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE) }
            val rise = minutes(sunrise)
            val set = minutes(sunset)
            val night = rise != null && set != null && (now < rise || now >= set)
            val s = summary.lowercase()
            return when {
                "thunder" in s -> "⛈️"
                "snow" in s -> "🌨️"
                "freezing" in s -> "🌧️"
                "heavy" in s || s == "rain" || "showers" in s -> "🌧️"
                "drizzle" in s || "light rain" in s -> if (night) "🌧️" else "🌦️"
                "fog" in s -> "🌫️"
                "overcast" in s -> "☁️"
                "partly" in s -> if (night) "☁️" else "⛅"
                "mostly clear" in s -> if (night) "🌙" else "🌤️"
                "clear" in s -> if (night) "🌙" else "☀️"
                else -> "🌡️"
            }
        }
    }
}
