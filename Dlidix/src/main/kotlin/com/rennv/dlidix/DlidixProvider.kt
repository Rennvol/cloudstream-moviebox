package com.rennv.dlidix

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Dlidix / IDLIX (z3.idlixku.com). Public JSON API, no login.
 * Playback: play-info -> redeem -> HLS master + subtitles.
 */
class DlidixProvider : MainAPI() {
    companion object {
        private const val WEB = "https://z3.idlixku.com"
        private const val API = "https://z3.idlixku.com/api"
        private const val IMG = "https://image.tmdb.org/t/p/w500"
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Origin" to WEB,
            "Referer" to "$WEB/",
        )
        private val MEDIA_HEADERS = mapOf("User-Agent" to UA)
        private val PLAY_HOSTS = listOf(WEB, "https://z1.idlixku.com", "https://api.idlixku.com")
    }

    override var name = "Dlidix"
    override var mainUrl = WEB
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/movies?sort=latest&page=" to "Film Terbaru",
        "/series?sort=latest&page=" to "Series Terbaru",
        "/movies?sort=popular&page=" to "Film Populer",
        "/series?sort=popular&page=" to "Series Populer",
        "/movies?sort=rating&page=" to "Film Rating Tertinggi",
    )

    private suspend fun api(
        path: String,
        body: String? = null,
        headers: Map<String, String> = HEADERS,
    ): JSONObject {
        val url = if (path.startsWith("http")) path else "$API$path"
        val res = if (body == null) app.get(url, headers = headers)
        else app.post(
            url,
            headers = headers,
            requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
        )
        val text = res.text
        if (res.code != 200) throw Exception("Dlidix HTTP ${res.code}: ${text.take(120)}")
        return JSONObject(text)
    }

    private fun image(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> IMG + path
    }

    private fun JSONObject.type(): TvType =
        if (optString("contentType") == "tv_series" || has("seasons") || has("numberOfSeasons")) TvType.TvSeries
        else TvType.Movie

    private fun JSONObject.itemUrl(): String? {
        val slug = optString("slug").takeIf { it.isNotBlank() } ?: return null
        val kind = if (type() == TvType.TvSeries) "series" else "movie"
        return "$kind|$slug"
    }

    private fun objArray(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }

    private fun JSONObject.toSearch(): SearchResponse? {
        val content = optJSONObject("content") ?: this
        if (optString("contentType").isNotBlank()) content.put("contentType", optString("contentType"))
        val url = content.itemUrl() ?: return null
        val title = content.optString("title").ifBlank { content.optString("originalTitle") }.takeIf { it.isNotBlank() }
            ?: return null
        return newMovieSearchResponse(title, url, content.type()) {
            posterUrl = image(content.optString("posterPath"))
            year = (content.optString("releaseDate").ifBlank { content.optString("firstAirDate") }).take(4).toIntOrNull()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = objArray(api(request.data + page).optJSONArray("data")).mapNotNull { it.toSearch() }
        return newHomePageResponse(listOf(HomePageList(request.name, list)))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList =
        objArray(api("/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page").optJSONArray("results"))
            .mapNotNull { it.toSearch() }
            .toNewSearchResponseList()

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|")
        val slug = parts.getOrNull(1).orEmpty()
        val root = api(if (parts[0] == "series") "/series/$slug" else "/movies/$slug")
        val title = root.optString("title").ifBlank { root.optString("originalTitle") }.ifBlank { "Dlidix" }
        val plot = listOfNotNull(
            root.optString("overview").takeIf { it.isNotBlank() },
            root.optString("tagline").takeIf { it.isNotBlank() }?.let { "Tagline: $it" },
        ).joinToString("\n\n")
        val poster = image(root.optString("posterPath"))
        val year = (root.optString("releaseDate").ifBlank { root.optString("firstAirDate") }).take(4).toIntOrNull()
        val score = root.optString("voteAverage").toDoubleOrNull()?.let { Score.from10(it.toString()) }
        val tags = objArray(root.optJSONArray("genres")).mapNotNull {
            it.optString("name").takeIf { n -> n.isNotBlank() }
        }
        val actors = objArray(root.optJSONArray("cast")).mapNotNull { c ->
            c.optString("name").takeIf { it.isNotBlank() }?.let { n ->
                ActorData(Actor(n, image(c.optString("profilePath"))), roleString = c.optString("character"))
            }
        }.distinctBy { it.actor.name }

        if (parts[0] != "series") {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
                this.actors = actors
                this.duration = root.optInt("runtime").takeIf { it > 0 }
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonNumbers = objArray(root.optJSONArray("seasons")).mapNotNull {
            it.optInt("seasonNumber", -1).takeIf { n -> n > 0 }
        }.ifEmpty { (1..root.optInt("numberOfSeasons", 0)).toList() }
        for (seasonNo in seasonNumbers) {
            try {
                val seasonRoot = api("/series/$slug/season/$seasonNo")
                addEpisodes(episodes, seasonRoot.optJSONObject("season") ?: seasonRoot, seasonNo)
            } catch (_: Exception) {
                // ponytail: satu season gagal jangan hapus season lain
            }
        }
        if (episodes.isEmpty()) {
            root.optJSONObject("defaultSeason")?.let { addEpisodes(episodes, it, it.optInt("seasonNumber", 1)) }
        }
        if (episodes.isEmpty()) throw Exception("Dlidix: series tanpa episode: $slug")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
            this.actors = actors
        }
    }

    private fun addEpisodes(target: MutableList<Episode>, season: JSONObject, seasonNo: Int) {
        for (ep in objArray(season.optJSONArray("episodes"))) {
            val epId = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
            val epNo = ep.optInt("episodeNumber", 0)
            if (epNo <= 0) continue
            target += newEpisode("$seasonNo|$epId", {
                this.season = seasonNo
                this.episode = epNo
                this.name = ep.optString("name").ifBlank { "Episode $epNo" }
                this.description = ep.optString("overview")
                this.posterUrl = image(ep.optString("stillPath"))
            }, false)
        }
    }

    private fun resolve(base: String, path: String): String =
        if (path.startsWith("http")) path else URI(base).resolve(path).toString()

    private suspend fun addCaptions(captions: JSONArray?, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        for (c in objArray(captions)) {
            val path = c.optString("path").ifBlank { c.optString("url") }
            if (path.isBlank()) continue
            val lang = c.optString("lang").ifBlank { c.optString("label") }.ifBlank { "Unknown" }
            subtitleCallback(newSubtitleFile(lang, resolve(base, path)) {
                this.headers = MEDIA_HEADERS
            })
        }
    }

    private suspend fun addLink(
        url: String,
        quality: Int,
        name: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(newExtractorLink(
            source = name,
            name = name,
            url = url,
            type = ExtractorLinkType.M3U8,
        ) {
            this.quality = if (quality > 0) quality else Qualities.Unknown.value
            this.headers = MEDIA_HEADERS
        })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        val isMovie = parts[0] == "movie"
        val uuid = if (isMovie) {
            api("/movies/${parts.getOrNull(1).orEmpty()}").optString("id")
        } else parts.getOrNull(1).orEmpty()
        if (uuid.isBlank()) throw Exception("Dlidix: id play tidak ditemukan")

        val kind = if (isMovie) "movie" else "episode"
        var playInfo: JSONObject? = null
        var lastError: Exception? = null
        for (host in PLAY_HOSTS) {
            try {
                val info = api("$host/api/watch/play-info/$kind/$uuid")
                if (info.optString("claim").isNotBlank() && info.optString("redeemUrl").isNotBlank()) {
                    playInfo = info
                    break
                }
                lastError = Exception("Dlidix: play-info kosong dari $host")
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (playInfo == null) throw lastError ?: Exception("Dlidix: play-info gagal")

        val play = api(
            playInfo.optString("redeemUrl"),
            JSONObject().put("claim", playInfo.optString("claim")).toString(),
            MEDIA_HEADERS,
        )
        val payload = play.optJSONObject("data") ?: play
        val master = payload.optString("url")
        if (master.isBlank()) throw Exception("Dlidix: redeem tanpa URL stream")

        addCaptions(payload.optJSONArray("subtitles"), master, subtitleCallback)
        val maxHeight = payload.optInt("maxHeight", 0)
        addLink(master, maxHeight, "Dlidix ${if (maxHeight > 0) "${maxHeight}p" else "auto"}", callback)

        // Master playlist lists per-resolution variants; expose each so 1080p is selectable.
        try {
            var pending = 0
            for (line in app.get(master, headers = MEDIA_HEADERS).text.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    pending = Regex("RESOLUTION=\\d+x(\\d+)").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("NAME=?\"?(\\d+)p").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val variant = resolve(master, trimmed)
                    if (variant != master) addLink(variant, pending, "Dlidix ${if (pending > 0) "${pending}p" else "auto"}", callback)
                    pending = 0
                }
            }
        } catch (_: Exception) {
            // ponytail: master link already emitted; variants are an enhancement
        }
        return true
    }
}
