package com.rennv.nontonwok

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
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class NontonWokProvider : MainAPI() {

    override var name = "NontonWok"
    override var mainUrl = "https://nonton.work"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/|movie" to "Trending Movies",
        "/|series" to "Trending Series",
        "/movies" to "Movies",
        "/tv-series" to "TV Series",
    )

    private suspend fun fetchText(url: String): String =
        app.get(url, headers = mapOf("User-Agent" to UA)).text

    private fun extractNextF(html: String): String {
        val re = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        re.findAll(html).forEach { m ->
            val raw = m.groupValues[1]
            sb.append(
                raw.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
            )
        }
        return sb.toString()
    }

    private fun extractItems(big: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        // RSC payload embeds items arrays as JSON-ish; parse every `"items":[{...}]` block we can.
        val marker = "\"items\":["
        var i = 0
        while (true) {
            val start = big.indexOf(marker, i)
            if (start < 0) break
            val arrStart = start + marker.length - 1
            val arrStr = extractJsonArray(big, arrStart) ?: run { i = arrStart + 1; continue }
            runCatching { JSONArray(arrStr) }.getOrNull()?.objects()?.forEach { obj ->
                val sid = obj.optString("subjectId")
                if (sid.isNotBlank() && seen.add(sid)) out += obj
            }
            i = arrStart + arrStr.length
        }
        // fallback: single objects with subjectId+detailPath
        if (out.isEmpty()) {
            val objRe = Regex("""\{"subjectId":"?\d+"?.*?"detailPath":"[^"]+"\}""")
            objRe.findAll(big).forEach { m ->
                runCatching { JSONObject(m.value) }.getOrNull()?.let { obj ->
                    val sid = obj.optString("subjectId")
                    if (sid.isNotBlank() && seen.add(sid)) out += obj
                }
            }
        }
        return out
    }

    private fun extractJsonArray(s: String, start: Int): String? {
        if (start !in s.indices || s[start] != '[') return null
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.toSearch(want: TvType? = null): SearchResponse? {
        val id = optString("subjectId").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        val detailPath = optString("detailPath").trim()
        val type = if (optInt("subjectType", 1) in listOf(2, 7)) TvType.TvSeries else TvType.Movie
        if (want != null && type != want) return null
        val cover = optJSONObject("cover")?.optString("url")?.takeIf { it.startsWith("http") }
            ?: optString("poster").takeIf { it.startsWith("http") }
        val url = if (detailPath.isNotBlank()) "$id|$detailPath" else "$id|"
        return newMovieSearchResponse(title, url, type) {
            posterUrl = cover
            this.year = optString("releaseDate").take(4).toIntOrNull()
                ?: optString("year").toIntOrNull()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore("|")
        val wantStr = request.data.substringAfter("|", "")
        val want = when (wantStr) {
            "movie" -> TvType.Movie
            "series" -> TvType.TvSeries
            else -> null
        }
        val url = if (page <= 1) "$mainUrl$path" else "$mainUrl$path?page=$page"
        val html = fetchText(url)
        val big = extractNextF(html)
        val items = extractItems(big).mapNotNull { it.toSearch(want) }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val html = fetchText(url)
        val big = extractNextF(html)
        val list = extractItems(big).mapNotNull { it.toSearch() }
            .ifEmpty {
                // fallback: /watch/ links on page
                Regex("""href="(/watch/([^"]+))"""").findAll(html).mapNotNull { m ->
                    val slug = m.groupValues[2]
                    val title = slug.substringBeforeLast("-").replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    if (title.isBlank()) null else newMovieSearchResponse(title, "|$slug", TvType.Movie)
                }.distinctBy { it.name }.toList()
            }
        return list.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|")
        val rawId = parts.getOrNull(0).orEmpty()
        var detailPath = parts.getOrNull(1).orEmpty()

        // if url is a full watch url or slug
        if (rawId.contains("/watch/")) detailPath = rawId.substringAfter("/watch/").substringBefore("?")
        if (rawId.isNotBlank() && rawId.all { it.isDigit() }.not() && detailPath.isBlank()) {
            detailPath = rawId.substringAfterLast("/")
        }

        val id = Regex("""\d{10,}""").find(rawId)?.value ?: ""
        val safePath = detailPath.ifBlank { rawId }.ifBlank { id }
        val pageUrl = "$mainUrl/play/$safePath"
        val html = fetchText(pageUrl)
        val big = extractNextF(html)

        val subjectId = Regex("""\\"subjectId\\":\\"?(\d+)\\"?""").find(big)?.groupValues?.get(1)
            ?: Regex(""""subjectId":"?(\d+)"?""").find(big)?.groupValues?.get(1)
            ?: id
        val contentType = Regex("""\\"contentType\\":\\"(movie|series)\\"""").find(big)?.groupValues?.get(1)
            ?: Regex(""""contentType":"(movie|series)"""").find(big)?.groupValues?.get(1)
            ?: "movie"
        val title = Regex("""\\"name\\":\\"([^\\"]+)\\"""").find(big)?.groupValues?.get(1)
            ?: Regex("""\\"title\\":\\"([^\\"]+)\\"""").find(big)?.groupValues?.get(1)
            ?: Regex(""""title":"([^"]+)"""").find(big)?.groupValues?.get(1)
            ?: safePath.substringBeforeLast("-").replace("-", " ").ifBlank { "NontonWok" }
        val poster = Regex("""\\"image\\":\\"(https?://[^\\"]+)\\"""").find(big)?.groupValues?.get(1)
            ?: Regex(""""image":"(https?://[^"]+)"""").find(big)?.groupValues?.get(1)
        val desc = Regex("""\\"description\\":\\"([^\\"]*)\\"""").find(big)?.groupValues?.get(1)
            ?: Regex(""""description":"([^"]*)"""").find(big)?.groupValues?.get(1).orEmpty()
        val rating = Regex(""""rating":"([^"]+)"""").find(big)?.groupValues?.get(1)?.toDoubleOrNull()
        val year = Regex(""""releaseDate":"(\d{4})"""").find(big)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex(""""year":"?(\d{4})"?""").find(big)?.groupValues?.get(1)?.toIntOrNull()

        val dataId = "$subjectId|$safePath|$contentType"

        return if (contentType == "series") {
            val epList = mutableListOf<Episode>()
            // Parse seasons/episodes from /play RSC payload (VideoPlayer props)
            if (big.isNotBlank()) {
                val seasonsMatch = Regex("""\\\"seasons\\\":\[(.*?)\]\\s*,\\s*\\\"autoPlay\\\"""", RegexOption.DOT_MATCHES_ALL).find(big)
                    ?: Regex(""""seasons":\[(.*?)\]\s*,\s*"autoPlay"""", RegexOption.DOT_MATCHES_ALL).find(big)
                val seasonsStr = seasonsMatch?.groupValues?.get(1).orEmpty()
                    .replace("\\\"", "\"")
                if (seasonsStr.isNotBlank()) {
                    val seasonRe = Regex("""\{"season":(\d+),"episodeCount":\d+,"episodes":\[(.*?)]\}""")
                    seasonRe.findAll(seasonsStr).forEach { sm ->
                        val sn = sm.groupValues[1].toIntOrNull() ?: return@forEach
                        val epsStr = sm.groupValues[2]
                        Regex("""\{"season":\d+,"episode":(\d+),"available":(true|false)\}""").findAll(epsStr).forEach { em ->
                            val en = em.groupValues[1].toIntOrNull() ?: return@forEach
                            val avail = em.groupValues[2] == "true"
                            if (avail) {
                                epList += newEpisode("$dataId|$sn|$en") {
                                    this.season = sn
                                    this.episode = en
                                    this.name = "Episode $en"
                                }
                            }
                        }
                    }
                }
            }
            if (epList.isEmpty()) {
                epList += newEpisode("$dataId|1|1") {
                    this.season = 1
                    this.episode = 1
                    this.name = "Episode 1"
                }
            }
            // ponytail: cast/actors not exposed in RSC payload; metadata from /watch fallback below
            var metaPoster = poster
            var metaDesc = desc
            val watchBig = runCatching { extractNextF(fetchText("$mainUrl/watch/$safePath")) }.getOrNull().orEmpty()
            if (watchBig.isNotBlank()) {
                if (metaPoster.isNullOrBlank()) {
                    metaPoster = Regex("""\\\"image\\\":\\\"(https?://[^\\\"]+)\\\"""").find(watchBig)?.groupValues?.get(1)
                        ?: Regex(""""image":"(https?://[^"]+)"""").find(watchBig)?.groupValues?.get(1)
                }
                if (metaDesc.isBlank()) {
                    metaDesc = Regex("""\\\"description\\\":\\\"([^\\\"]*)\\\"""").find(watchBig)?.groupValues?.get(1)
                        ?: Regex(""""description":"([^"]*)"""").find(watchBig)?.groupValues?.get(1).orEmpty()
                }
            }
            newTvSeriesLoadResponse(title, dataId, TvType.TvSeries, epList) {
                this.posterUrl = metaPoster
                this.plot = metaDesc
                this.year = year
                this.score = rating?.let { Score.from10(it.toString()) }
            }
        } else {
            var metaPoster = poster
            var metaDesc = desc
            val watchBig = runCatching { extractNextF(fetchText("$mainUrl/watch/$safePath")) }.getOrNull().orEmpty()
            if (watchBig.isNotBlank()) {
                if (metaPoster.isNullOrBlank()) {
                    metaPoster = Regex("""\\\"image\\\":\\\"(https?://[^\\\"]+)\\\"""").find(watchBig)?.groupValues?.get(1)
                        ?: Regex(""""image":"(https?://[^"]+)"""").find(watchBig)?.groupValues?.get(1)
                }
                if (metaDesc.isBlank()) {
                    metaDesc = Regex("""\\\"description\\\":\\\"([^\\\"]*)\\\"""").find(watchBig)?.groupValues?.get(1)
                        ?: Regex(""""description":"([^"]*)"""").find(watchBig)?.groupValues?.get(1).orEmpty()
                }
            }
            newMovieLoadResponse(title, dataId, TvType.Movie, "$dataId|0|0") {
                this.posterUrl = metaPoster
                this.plot = metaDesc
                this.year = year
                this.score = rating?.let { Score.from10(it.toString()) }
            }
        }
    }

    private fun isPlayable(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val u = url.lowercase()
        return !u.contains("deprecation") && !u.contains("placeholder") && !u.contains("anti_piracy")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        val subjectId = parts.getOrNull(0).orEmpty()
        val detailPath = parts.getOrNull(1).orEmpty()
        val contentType = parts.getOrNull(2).orEmpty().ifBlank { "movie" }
        val season = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val episode = parts.getOrNull(4)?.toIntOrNull() ?: 0

        val streamUrl = "$mainUrl/api/stream?subjectId=$subjectId&detailPath=$detailPath&contentType=$contentType&season=$season&episode=$episode"
        val json = runCatching { JSONObject(app.get(streamUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text) }.getOrNull()
            ?: return false
        if (!json.optBoolean("success", false)) return false
        val root = json.optJSONObject("data") ?: return false

        var found = false

        suspend fun emit(url: String?, quality: Int, label: String, isDash: Boolean = false) {
            val u = url?.takeIf { isPlayable(it) } ?: return
            val q = if (quality > 0) quality else Qualities.Unknown.value
            val type = when {
                isDash || u.contains(".mpd") -> ExtractorLinkType.DASH
                u.contains(".m3u8") -> ExtractorLinkType.M3U8
                else -> INFER_TYPE
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label".trim(),
                    url = u,
                    type = type,
                ) {
                    this.quality = q
                    this.referer = mainUrl
                    // cdn-proxy returns 403 without browser UA (direct CDN returns 429)
                    this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
                }
            )
            found = true
        }

        fun pickUrl(s: JSONObject): String? {
            val proxy = s.optBoolean("proxyRequired", false)
            val p = s.optString("proxyUrl")
            if (proxy && p.isNotBlank()) return if (p.startsWith("http")) p else "$mainUrl$p"
            return s.optString("url").takeIf { it.isNotBlank() }
        }

        root.optJSONArray("streams")?.objects()?.forEach { s ->
            val q = s.optString("resolution").replace("p", "").toIntOrNull() ?: 0
            emit(pickUrl(s), q, s.optString("resolution").ifBlank { "mp4" })
        }
        root.optJSONArray("hls")?.objects()?.forEach { s ->
            val q = s.optString("resolution").replace("p", "").toIntOrNull() ?: 0
            emit(pickUrl(s), q, s.optString("resolution").ifBlank { "hls" })
        }

        // captions best-effort: try stream ids
        val streamId = root.optJSONArray("streams")?.optJSONObject(0)?.optString("id").orEmpty()
        if (streamId.isNotBlank()) {
            val capUrl = "$mainUrl/api/captions?subjectId=$subjectId&detailPath=$detailPath&streamId=$streamId&format=mp4"
            val caps = runCatching { JSONObject(app.get(capUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text) }.getOrNull()
            caps?.optJSONObject("data")?.optJSONArray("list")?.objects()?.forEach { c ->
                val u = c.optString("url").takeIf { it.startsWith("http") } ?: return@forEach
                val lang = c.optString("language").ifBlank { c.optString("lan").ifBlank { "Sub" } }
                subtitleCallback(newSubtitleFile(u, lang))
            }
        }

        return found
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
