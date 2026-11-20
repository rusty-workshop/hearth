package com.rusty.hearth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/** One background photo, decoded and ready to show. [title] is its Commons file name. */
class Photo(val bitmap: Bitmap, val credit: String, val title: String, val url: String)

/**
 * Random nature photos from Wikimedia Commons' Featured pictures. Commons
 * doesn't allow watermarks on featured pictures, and needs no account or key.
 * Shared by the home screen and the screensaver: use [Photos.get].
 *
 * - From 9pm to 6am photos come from the twilight and night categories.
 * - Photos can be saved (and shown exclusively with "saved only") or hidden for good.
 *
 * Wikimedia rate-limits anonymous clients hard (HTTP 429), so this goes easy:
 * the list of candidate file names is rebuilt at most once a week, one
 * category at a time with a pause between, and cached in filesDir; image
 * details are fetched 20 at a time and queued.
 *
 * Network calls block; call [next] from a worker thread.
 */
class Photos private constructor(context: Context) {

    private val poolFile = File(context.filesDir, "photo-pool-v2.json")
    private val lastFile = File(context.filesDir, "last-photo.jpg")
    private val lastMeta = File(context.filesDir, "last-photo.json")
    private val prefs = context.getSharedPreferences("photos", Context.MODE_PRIVATE)
    /** Recently downloaded photos, reused when Wikimedia is unreachable or rate-limiting us. */
    private val cacheDir = File(context.filesDir, "photo-cache").apply { mkdirs() }
    private var backoffUntil = 0L
    private var pool: Map<String, List<String>> = emptyMap()
    private val queue = ArrayDeque<Candidate>()
    private var queueIsNight = false
    private var lastTitle: String? = null

    private class Candidate(val url: String, val credit: String, val title: String)

    // ---- saved / hidden -----------------------------------------------------
    // Plain SharedPreferences, deliberately not locked: next() holds the lock during
    // downloads, and these run when someone presses a button.

    fun isSaved(title: String) = saved().any { it.title == title }

    fun savedCount() = saved().size

    /** Save or unsave; returns whether it's saved now. */
    fun toggleSaved(photo: Photo): Boolean {
        val list = saved().toMutableList()
        val was = list.removeAll { it.title == photo.title }
        if (!was) list += Candidate(photo.url, photo.credit, photo.title)
        prefs.edit().putString(SAVED, JSONArray(list.map {
            JSONObject().put("url", it.url).put("credit", it.credit).put("title", it.title)
        }).toString()).apply()
        if (list.isEmpty()) writeSavedOnly(false)
        return !was
    }

    /** Never show this photo again (also unsaves it). */
    fun hide(photo: Photo) {
        if (isSaved(photo.title)) toggleSaved(photo)
        prefs.edit().putStringSet(HIDDEN, hidden() + photo.title).apply()
    }

    var savedOnly: Boolean
        get() = prefs.getBoolean(SAVED_ONLY, false) && savedCount() > 0
        set(value) = writeSavedOnly(value)

    private fun writeSavedOnly(value: Boolean) = prefs.edit().putBoolean(SAVED_ONLY, value).apply()

    private fun hidden(): Set<String> = prefs.getStringSet(HIDDEN, emptySet())!!

    private fun saved(): List<Candidate> = runCatching {
        val a = JSONArray(prefs.getString(SAVED, "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Candidate(o.getString("url"), o.getString("credit"), o.getString("title")) } }
    }.getOrDefault(emptyList())

    // ---- photos -------------------------------------------------------------

    /** The photo from last time, if any. */
    fun last(): Photo? = runCatching {
        val bmp = BitmapFactory.decodeFile(lastFile.path, opts()) ?: return null
        val meta = runCatching { JSONObject(lastMeta.readText()) }.getOrNull()
        if (meta != null) Photo(bmp, meta.getString("credit"), meta.getString("title"), meta.getString("url"))
        // Hearth 1.1 kept just the credit, in last-photo.txt.
        else Photo(bmp, runCatching { File(lastFile.parentFile, "last-photo.txt").readText() }.getOrDefault(""), "", "")
    }.getOrNull()

    /** A fresh random photo, or null if Wikimedia can't be reached right now. */
    @Synchronized fun next(): Photo? {
        repeat(4) {
            val c = (if (System.currentTimeMillis() < backoffUntil) null else pick()) ?: return fromCache()
            val bytes = runCatching { get(c.url) }.onFailure { Log.w(TAG, "image: $it") }.getOrNull() ?: return@repeat
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts()) ?: return@repeat
            lastTitle = c.title
            runCatching {
                lastFile.writeBytes(bytes)
                lastMeta.writeText(meta(c).toString())
                remember(c, bytes)
            }
            return Photo(bmp, c.credit, c.title, c.url)
        }
        return fromCache()
    }

    private fun meta(c: Candidate) = JSONObject().put("credit", c.credit).put("title", c.title).put("url", c.url)

    /** Keep the newest [CACHE_MAX] downloads on disk. */
    private fun remember(c: Candidate, bytes: ByteArray) {
        val name = Integer.toHexString(c.title.hashCode())
        File(cacheDir, "$name.jpg").writeBytes(bytes)
        File(cacheDir, "$name.json").writeText(meta(c).toString())
        cacheDir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }
            ?.drop(CACHE_MAX)?.forEach { it.delete(); File(it.path.removeSuffix(".jpg") + ".json").delete() }
    }

    /** A random previously downloaded photo (not hidden, not the one just shown, saved-only respected). */
    private fun fromCache(): Photo? {
        val hidden = hidden()
        val savedTitles = if (savedOnly) saved().map { it.title }.toSet() else null
        val choices = cacheDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { JSONObject(f.readText()) to File(f.path.removeSuffix(".json") + ".jpg") }.getOrNull()
        }.filter { (m, jpg) ->
            val t = m.optString("title")
            jpg.exists() && t !in hidden && t != lastTitle && (savedTitles == null || t in savedTitles)
        }
        val (m, jpg) = choices.randomOrNull() ?: return last()
        val bmp = BitmapFactory.decodeFile(jpg.path, opts()) ?: return null
        lastTitle = m.getString("title")
        return Photo(bmp, m.getString("credit"), m.getString("title"), m.getString("url"))
    }

    private fun pick(): Candidate? {
        if (savedOnly) {
            val list = saved()
            return list.filter { it.title != lastTitle }.randomOrNull() ?: list.randomOrNull()
        }
        val night = isNight()
        if (night != queueIsNight) { queue.clear(); queueIsNight = night }
        if (queue.isEmpty()) refill(night)
        val hidden = hidden()
        while (queue.isNotEmpty()) queue.removeFirst().let { if (it.title !in hidden) return it }
        return null
    }

    private fun refill(night: Boolean) {
        if (pool.isEmpty()) pool = loadPool()
        val hidden = hidden()
        val nature = CATEGORIES.flatMap { pool[it].orEmpty() }.distinct()
            .filter { it !in hidden && JPEG.containsMatchIn(it) && !NOT_NATURE.containsMatchIn(it) }
        // At night: sunsets, dusk, moon, stars and auroras from the same nature categories.
        val titles = if (night) nature.filter { NIGHTISH.containsMatchIn(it) }.ifEmpty { nature } else nature
        if (titles.isEmpty()) return
        val q = "action=query&format=json&prop=imageinfo&iiprop=url|size|extmetadata" +
            "&iiextmetadatafilter=Artist|ObjectName&iiurlwidth=$WIDTH&titles=" +
            URLEncoder.encode(titles.shuffled().take(20).joinToString("|"), "UTF-8")
        val pages = runCatching { JSONObject(String(get("$API?$q"))).getJSONObject("query").getJSONObject("pages") }
            .onFailure { Log.w(TAG, "imageinfo: $it") }.getOrNull() ?: return
        for (key in pages.keys()) {
            val page = pages.getJSONObject(key)
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val w = info.optInt("width")
            val h = info.optInt("height")
            // Landscape only, not so wide that a 16:9 crop throws most of it away, and big enough for the TV.
            if (h == 0 || w < WIDTH || w.toFloat() / h !in 1.3f..2.2f) continue
            val url = info.optString("thumburl")
            if (url.isEmpty()) continue
            val title = page.optString("title")
            // The landscape categories include towns and buildings; keep it to nature.
            if (NOT_NATURE.containsMatchIn(title)) continue
            val meta = info.optJSONObject("extmetadata")
            val name = tidy(plain(meta?.optJSONObject("ObjectName")?.optString("value"))
                .ifEmpty { title.removePrefix("File:").substringBeforeLast('.') })
            val artist = plain(meta?.optJSONObject("Artist")?.optString("value"))
            queue += Candidate(url, listOf(name, artist).filter { it.isNotEmpty() }.joinToString("  ·  "), title)
        }
    }

    /** File names per category, cached for a week. */
    private fun loadPool(): Map<String, List<String>> {
        fun parse(o: JSONObject) = o.getJSONObject("cats").let { cats ->
            cats.keys().asSequence().associateWith { k -> cats.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } }
        }
        val cached = runCatching { JSONObject(poolFile.readText()) }.getOrNull()
        if (cached != null && System.currentTimeMillis() - cached.optLong("at") < WEEK_MS) return parse(cached)

        val out = LinkedHashMap<String, List<String>>()
        for ((i, cat) in CATEGORIES.withIndex()) {
            if (i > 0) Thread.sleep(1500)
            val titles = ArrayList<String>()
            var cont = ""
            do {
                val q = "action=query&format=json&list=categorymembers&cmtype=file&cmlimit=500" +
                    "&cmtitle=" + URLEncoder.encode("Category:$cat", "UTF-8") + cont
                val json = runCatching { JSONObject(String(get("$API?$q"))) }
                    .onFailure { Log.w(TAG, "category $cat: $it") }.getOrNull() ?: break
                val members = json.getJSONObject("query").getJSONArray("categorymembers")
                for (j in 0 until members.length()) titles += members.getJSONObject(j).getString("title")
                cont = json.optJSONObject("continue")?.optString("cmcontinue")
                    ?.let { "&cmcontinue=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
                if (cont.isNotEmpty()) Thread.sleep(1500)
            } while (cont.isNotEmpty())
            if (titles.isNotEmpty()) out[cat] = titles
        }
        // Offline or rate-limited: fall back to a stale list if there is one.
        if (out.isEmpty()) return cached?.let(::parse).orEmpty()
        runCatching {
            val cats = JSONObject().apply { out.forEach { (k, v) -> put(k, JSONArray(v)) } }
            poolFile.writeText(JSONObject().put("at", System.currentTimeMillis()).put("cats", cats).toString())
        }
        return out
    }

    // ---- helpers ------------------------------------------------------------

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode == 429) {
                // Back off for what Wikimedia asks, at least 10 minutes; cached photos fill in meanwhile.
                val wait = (conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0L) * 1000
                backoffUntil = System.currentTimeMillis() + maxOf(wait, 10 * 60_000L)
            }
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode} for ${url.take(120)}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Half the memory of ARGB_8888, and photos don't need alpha. */
    private fun opts() = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    /** Commons metadata is HTML: strip tags and entities, keep it short. */
    private fun plain(html: String?): String = android.text.Html.fromHtml(html.orEmpty(), 0)
        .toString().replace(Regex("\\s+"), " ").trim().take(80)

    /**
     * Commons titles are often camera filenames: "Albi 2014-02-22 19-52-00 - Tarn river at dusk - 7236".
     * Drop dates, times, frame numbers and underscores, keeping the words.
     */
    private fun tidy(name: String): String = name
        .replace('_', ' ')
        .replace(Regex("\\b\\d{4}[-.]\\d{2}[-.]\\d{2}\\b|\\b\\d{1,2}[-:.]\\d{2}([-:.]\\d{2})?\\b"), " ")
        .replace(Regex("\\b(IMG|DSC|DSCF|DJI|PANO)?\\d{3,}\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\(\\s*\\)"), " ")
        .replace(Regex("^\\s*\\d{1,2}\\s+"), "")  // "1 tianzishan …" → "tianzishan …"
        .split(Regex("\\s+-\\s+|\\s+—\\s+"))
        .map { it.trim(' ', ',', '-', '–') }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
        .replace(Regex("\\s+"), " ")

    companion object {
        @Volatile private var instance: Photos? = null

        /** One shared instance per process, so the home screen and screensaver share a queue. */
        fun get(context: Context): Photos =
            instance ?: synchronized(this) { instance ?: Photos(context.applicationContext).also { instance = it } }

        /** 9pm to 6am. */
        fun isNight(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 21 || it < 6 }

        private val NOT_NATURE = Regex(
            "\\b(town|city|village|street|church|cathedral|castle|palace|temple|monastery|abbey|" +
                "bridge|harbou?r|skyline|downtown|old town|chapel|mosque|tower|square|station|cityscape|night of)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val CATEGORIES = listOf(
            "Featured pictures of landscapes", "Featured pictures of mountains",
            "Featured pictures of bodies of water", "Featured pictures of coasts",
            "Featured pictures of forests", "Featured pictures of islands",
            "Featured pictures of volcanoes", "Featured pictures of natural phenomena",
        )
        private val NIGHTISH = Regex("sunset|sunrise|dusk|dawn|twilight|night|moon|star|milky|aurora|evening|blue hour|golden hour",
            RegexOption.IGNORE_CASE)
        private val JPEG = Regex("\\.jpe?g$", RegexOption.IGNORE_CASE)
        private const val CACHE_MAX = 80
        private const val TAG = "Hearth"
        private const val API = "https://commons.wikimedia.org/w/api.php"
        private const val USER_AGENT = "Hearth/1.2 (home TV launcher; personal use)"
        private const val WIDTH = 1920
        private const val WEEK_MS = 7 * 24 * 3600_000L
        private const val SAVED = "saved"
        private const val HIDDEN = "hidden"
        private const val SAVED_ONLY = "savedOnly"
    }
}
