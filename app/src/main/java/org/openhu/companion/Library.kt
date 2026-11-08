package org.openhu.companion

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The music plugin's library (org.openhu.media, `music.query`) as a browse tree. Media ids:
 * the tabs "albums", "artists", "playlists", "songs"; "album|<id>", "artist|<id>", "playlist|<id>";
 * a track "play|<context kind>|<context id>|<track id>" (played with `music.command playItems`).
 */
object Library {
    const val ROOT = "root"
    val TABS = listOf("albums", "artists", "playlists", "songs")
    private const val LIMIT = 500

    data class Entry(val id: String, val title: String, val subtitle: String?, val playable: Boolean)

    data class Play(val kind: String, val contextId: String, val track: String)

    fun join(vararg parts: String): String = parts.joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    fun split(id: String): List<String> = id.split("|").map { URLDecoder.decode(it, "UTF-8") }

    /** The `music.query` data for a browsable id, null for an unknown one. */
    fun query(parentId: String): JSONObject? {
        val parts = split(parentId)
        val query = JSONObject().put("offset", 0).put("limit", LIMIT)
        return when (parts[0]) {
            in TABS -> query.put("cmd", parts[0])
            "album" -> query.put("cmd", "album").put("album", parts.getOrElse(1) { "" })
            "artist" -> query.put("cmd", "artist").put("artist", parts.getOrElse(1) { "" })
            "playlist" -> query.put("cmd", "playlist").put("playlist", parts.getOrElse(1) { "" })
            else -> null
        }
    }

    /** The children from a `music.result`: album, playlist pages carry "tracks", the lists and an artist "items". */
    fun entries(parentId: String, result: JSONObject): List<Entry> {
        if (!result.optBoolean("ok")) return emptyList()
        val parent = split(parentId)
        val tracks = result.optJSONArray("tracks")
        val list = tracks ?: result.optJSONArray("items") ?: return emptyList()
        val entries = ArrayList<Entry>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val kind = item.optString("kind", if (tracks != null) "song" else "")
            if (tracks != null || kind == "song") {
                if (!item.optBoolean("available", true) || id.isEmpty()) continue
                val contextKind = if (parent[0] == "songs") "songs" else parent[0]
                val contextId = if (parent[0] == "songs") "" else parent.getOrElse(1) { "" }
                entries += Entry(join("play", contextKind, contextId, id), item.optString("title"),
                    item.optString("artist").ifEmpty { null }, playable = true)
            } else if (kind == "album" || kind == "artist" || kind == "playlist") {
                entries += Entry(join(kind, id), item.optString("title"), item.optString("subtitle").ifEmpty { null },
                    playable = false)
            }
        }
        return entries
    }

    /** The song of a `songs` result that fits a spoken query best (all words in title, artist or album). */
    fun bestSong(query: String, result: JSONObject): Entry? {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in FILLER }
        if (words.isEmpty()) return null
        val items = result.optJSONArray("items") ?: return null
        var best: Pair<Int, Int>? = null  // score, index
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!item.optBoolean("available", true)) continue
            val title = item.optString("title").lowercase()
            val other = (item.optString("artist") + " " + item.optString("album")).lowercase()
            if (!words.all { it in title || it in other }) continue
            val score = words.count { it in title } * 2 + if (title == query.lowercase().trim()) 10 else 0
            if (best == null || score > best.first) best = score to i
        }
        val index = best?.second ?: return null
        val item = items.getJSONObject(index)
        return Entry(join("play", "songs", "", item.optString("id")), item.optString("title"),
            item.optString("artist").ifEmpty { null }, playable = true)
    }

    private val FILLER = setOf("by", "from", "von", "the", "song", "titel")

    fun play(mediaId: String): Play? {
        val parts = split(mediaId)
        if (parts.size < 4 || parts[0] != "play") return null
        return Play(parts[1], parts[2], parts[3])
    }

    /** `music.command playItems` for a track id from [entries]. */
    fun playCommand(play: Play): JSONObject {
        val context = JSONObject().put("kind", play.kind)
        if (play.contextId.isNotEmpty()) context.put("id", play.contextId)
        return JSONObject().put("cmd", "playItems").put("context", context).put("start", play.track)
    }
}
