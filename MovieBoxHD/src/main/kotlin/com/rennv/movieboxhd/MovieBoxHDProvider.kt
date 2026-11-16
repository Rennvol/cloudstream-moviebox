package com.rennv.movieboxhd

import android.net.Uri
import android.util.Base64
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
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MovieBoxHD (moviebox.ph) provider - kategori sama web.
 *
 * Talks to the mobile BFF (wefeed-mobile-bff) which every request signs:
 *  - x-client-token : "<ms>,<md5(reversed ms)>"
 *  - x-tr-signature : "<ms>|2|<base64 hmac-md5(canonical)>"
 *  - x-client-info  : device fingerprint json
 * An anonymous bearer token comes back in the `x-user` response header and is
 * required by search / play-info / resource.
 */
class MovieBoxHDProvider : MainAPI() {

    companion object {
        private val HOSTS = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
            "https://api6sg.aoneroom.com",
            "https://api.inprovider.com",
        )

        // The signing key is base64 of another base64 string. One decode gives
        // 40 printable chars, the real HMAC key is the second decode (30 bytes).
        private const val KEY_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        private const val KEY_ALT_B64 = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

        private const val MODEL = "22101316G"
        private const val UA =
            "com.community.oneroom/50020042 (Linux; U; Android 14; en_IN; $MODEL; " +
                "Build/UD1A.230803.041; Cronet/145.0.7582.0)"

        private val DEVICE_ID: String = (1..32)
            .map { "0123456789abcdef".random() }
            .joinToString("")

        private val CLIENT_INFO = JSONObject()
            .put("package_name", "com.community.oneroom")
            .put("version_name", "3.0.03.0529.03")
            .put("version_code", 50020042)
            .put("os", "android")
            .put("os_version", "14")
            .put("device_id", DEVICE_ID)
            .put("install_store", "official")
            .put("gaid", "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d")
            .put("brand", "xiaomi")
            .put("model", MODEL)
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "US")
            .put("timezone", "Asia/Jakarta")
            .put("sp_code", "")
            .toString()

        @Volatile
        private var bearer: String? = null
    }

    override var name = "MovieBoxHD"
    override var mainUrl = HOSTS[0]
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "ranking:4809349160627587984" to "Trending",
        "list:1" to "Movies",
        "list:2" to "Series",
        "filter:2|{\"classify\":\"All\",\"country\":\"United States\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Hollywood",
        "filter:5|{\"classify\":\"All\",\"country\":\"United States\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Western TV",
        "filter:5|{\"country\":\"Korea\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "K-Drama",
        "filter:5|{\"classify\":\"All\",\"country\":\"China\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "C-Drama",
        "filter:5|{\"classify\":\"All\",\"country\":\"All\",\"genre\":\"Anime\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Anime",
        "filter:2|{\"classify\":\"All\",\"country\":\"Indonesia\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Indo Film",
        "filter:5|{\"country\":\"Indonesia\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Indo Drama",
        "filter:5|{\"classify\":\"Indonesian dub\",\"country\":\"All\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Indo Dubbed",
        "filter:2|{\"classify\":\"All\",\"country\":\"Indonesia\",\"genre\":\"Horror\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Horror Indo",
        "filter:5|{\"classify\":\"All\",\"country\":\"India\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "India Drama",
        "filter:2|{\"classify\":\"All\",\"country\":\"India\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "India Film",
        "filter:5|{\"classify\":\"All\",\"country\":\"Thailand\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "Thai-Drama",
        "filter:5|{\"classify\":\"All\",\"country\":\"Japan\",\"genre\":\"All\",\"sort\":\"Hottest\",\"year\":\"All\"}" to "J-Drama",
        "filter:7|{\"sort\":\"Hottest\"}" to "Short TV",
    )

    // ---------------------------------------------------------------- signing

    private fun md5Hex(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    private fun base64Bytes(value: String): ByteArray =
        Base64.decode(value, Base64.DEFAULT)

    private fun xClientToken(ts: Long): String =
        "$ts," + md5Hex(ts.toString().reversed().toByteArray())

    private fun canonicalUrl(url: String): String {
        val uri = Uri.parse(url)
        val path = uri.path ?: ""
        val names = uri.queryParameterNames
        if (names.isEmpty()) return path
        val query = names.sorted().joinToString("&") { key ->
            uri.getQueryParameters(key).joinToString("&") { value -> "$key=$value" }
        }
        return "$path?$query"
    }

    private fun canonicalString(
        method: String,
        accept: String,
        contentType: String,
        url: String,
        body: String?,
        ts: Long,
    ): String {
        val bytes = body?.toByteArray()
        val bodyHash = if (bytes != null) md5Hex(bytes.copyOfRange(0, minOf(bytes.size, 102400))) else ""
        val bodyLength = bytes?.size?.toString() ?: ""
        return listOf(
            method.uppercase(), accept, contentType, bodyLength,
            ts.toString(), bodyHash, canonicalUrl(url),
        ).joinToString("\n")
    }

    private fun xTrSignature(
        method: String,
        accept: String,
        contentType: String,
        url: String,
        body: String?,
        ts: Long,
        useAltKey: Boolean = false,
    ): String {
        val canonical = canonicalString(method, accept, contentType, url, body, ts)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(base64Bytes(String(base64Bytes(if (useAltKey) KEY_ALT_B64 else KEY_DEFAULT_B64))), "HmacMD5"))
        val sig = Base64.encodeToString(mac.doFinal(canonical.toByteArray()), Base64.NO_WRAP)
        return "$ts|2|$sig"
    }

    // ---------------------------------------------------------------- request

    private suspend fun request(
        pathWithQuery: String,
        method: String = "GET",
        body: String? = null,
        bootstrap: Boolean = false,
    ): JSONObject {
        val accept = "application/json"
        val contentType = if (body != null) "application/json; charset=utf-8" else "application/json"
        var lastError: String? = null

        for (host in HOSTS) {
            val url = host + pathWithQuery
            val ts = System.currentTimeMillis()
            val headers = mutableMapOf(
                "User-Agent" to UA,
                "Accept" to accept,
                "Content-Type" to contentType,
                "Connection" to "keep-alive",
                "x-client-token" to xClientToken(ts),
                "x-tr-signature" to xTrSignature(method, accept, contentType, url, body, ts),
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0",
            )
            if (!bootstrap) currentToken()?.let { headers["Authorization"] = "Bearer $it" }

            val response = try {
                if (method == "POST") {
                    app.post(url, headers = headers, requestBody = (body ?: "").toRequestBody(contentType.toMediaType()))
                } else {
                    app.get(url, headers = headers)
                }
            } catch (e: Exception) {
                lastError = e.message
                continue
            }

            response.headers["x-user"]?.let { absorbToken(it) }

            if (response.code == 200) {
                mainUrl = host
                return JSONObject(response.body?.string() ?: "{}")
            }
            lastError = "HTTP ${response.code}: ${(response.body?.string() ?: "").take(200)}"
            if (response.code !in listOf(400, 401, 403, 407, 429, 500, 502, 503, 504)) break
        }
        throw Exception("MovieBox request failed: $lastError")
    }

    private fun absorbToken(xUser: String) {
        try {
            JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }?.let { bearer = it }
        } catch (_: Exception) {
        }
    }

    private suspend fun currentToken(): String? {
        bearer?.let { return it }
        request("/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=", bootstrap = true)
        return bearer
    }

    private suspend fun dataOf(pathWithQuery: String, method: String = "GET", body: String? = null): JSONObject? {
        val root = request(pathWithQuery, method, body)
        return root.optJSONObject("data")
    }

    // ------------------------------------------------------------------ parse

    private fun subjectsOf(node: JSONObject?): List<JSONObject> {
        node ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        node.optJSONArray("subjectList")?.let { arr -> out += arr.objects() }
        node.optJSONArray("subjects")?.let { arr -> out += arr.objects() }
        node.optJSONArray("items")?.let { arr -> out += arr.objects() }
        node.optJSONArray("results")?.objects()?.forEach { r ->
            r.optJSONArray("subjects")?.let { arr -> out += arr.objects() }
        }
        return out
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.allowedContent(strictWestern: Boolean = false): Boolean {
        val title = optString("title").ifBlank { optString("name") }.trim()
        return title.isNotBlank()
    }

    private fun JSONObject.toSearchResponse(onlyType: TvType? = null): SearchResponse? {
        val id = optString("subjectId").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").ifBlank { optString("name") }.trim().takeIf { it.isNotBlank() } ?: return null
        if (!allowedContent()) return null
        val type = if (optInt("subjectType", 1) in listOf(2, 7)) TvType.TvSeries else TvType.Movie
        if (onlyType != null && type != onlyType) return null
        val cover = optJSONObject("cover")?.optString("url")?.takeIf { it.startsWith("http") }
        return newMovieSearchResponse(title, "$id|0|0", type) {
            posterUrl = cover
            this.year = optString("releaseDate").take(4).toIntOrNull()
        }
    }

    // ------------------------------------------------------------------ API

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, value) = request.data.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val onlyType = when {
            kind == "list" && value.startsWith("1") -> TvType.Movie
            kind == "list" && value.startsWith("2") -> TvType.TvSeries
            else -> null
        }
        val raw = when (kind) {
            "ranking" -> subjectsOf(
                dataOf("/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=$value&page=$page&perPage=20")
            )
            "filter" -> {
                val tabId = value.substringBefore("|").ifBlank { "2" }
                val filterJson = value.substringAfter("|", "{}")
                val base = try { JSONObject(filterJson) } catch (_: Exception) { JSONObject() }
                val body = JSONObject().put("page", page).put("perPage", 20).put("tabId", tabId)
                for (k in base.keys()) body.put(k, base.get(k))
                if (!body.has("classify")) body.put("classify", "All")
                if (!body.has("country")) body.put("country", "All")
                if (!body.has("genre")) body.put("genre", "All")
                if (!body.has("year")) body.put("year", "All")
                if (!body.has("sort")) body.put("sort", "Hottest")
                subjectsOf(dataOf("/wefeed-mobile-bff/subject-api/list", "POST", body.toString()))
            }
            else -> {
                val channelId = value.substringAfter("|", "1").ifBlank { "1" }
                val body = JSONObject()
                    .put("page", page).put("perPage", 20)
                    .put("channelId", channelId)
                    .put("classify", "All").put("country", "All")
                    .put("year", "All").put("genre", "All").put("sort", "Latest")
                    .toString()
                subjectsOf(dataOf("/wefeed-mobile-bff/subject-api/list", "POST", body))
            }
        }
        val sorted = if (kind == "list") raw.sortedByDescending { it.optString("releaseDate") } else raw
        val list = sorted.mapNotNull { it.toSearchResponse(onlyType) }
        return newHomePageResponse(listOf(HomePageList(request.name, list)))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val body = JSONObject()
            .put("page", page).put("perPage", 20).put("keyword", query)
            .toString()
        val list = subjectsOf(dataOf("/wefeed-mobile-bff/subject-api/search/v2", "POST", body))
            .mapNotNull { it.toSearchResponse() }
        return list.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val raw = url.substringBefore("|")
        val id = Regex("""\d{10,}""").find(raw)?.value ?: raw
        val root = request("/wefeed-mobile-bff/subject-api/get?subjectId=$id")
        val data = root.optJSONObject("data") ?: root.optJSONObject("subject") ?: JSONObject()
        val titleRaw = data.optString("title").ifBlank { data.optString("name") }
        val sid0 = data.optString("subjectId").ifBlank { data.optString("id") }.ifBlank { id }
        val hasDetail = data.length() > 0 && (titleRaw.isNotBlank() || sid0.isNotBlank())

        val title = titleRaw.substringBefore("[").trim().ifBlank { "MovieBoxHD $id" }
        val description = data.optString("description")
        val releaseDate = data.optString("releaseDate")
        val year = releaseDate.take(4).toIntOrNull()
        val tags = data.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val poster = data.optJSONObject("cover")?.optString("url")
        val isSeries = data.optInt("subjectType", 1) in listOf(2, 7)
        val score = data.optString("imdbRatingValue").toDoubleOrNull()?.let { Score.from10(it.toString()) }
        val actors = data.optJSONArray("staffList")?.objects()
            ?.filter { it.optInt("staffType") == 1 }
            ?.mapNotNull { staff ->
                staff.optString("name").takeIf { it.isNotBlank() }?.let { n ->
                    ActorData(Actor(n, staff.optString("avatarUrl")), roleString = staff.optString("character"))
                }
            }?.distinctBy { it.actor.name } ?: emptyList()

        if (!isSeries) {
            return newMovieLoadResponse(title, "$id|0|0", TvType.Movie, "$id|0|0") {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
                this.duration = parseDuration(data.optString("duration"))
            }
        }

        // Series: seasons come from season-info, one call per dub (language variant).
        val subjectIds = mutableListOf(id)
        data.optJSONArray("dubs")?.objects()?.forEach { dub ->
            dub.optString("subjectId").takeIf { it.isNotBlank() && it !in subjectIds }?.let { subjectIds += it }
        }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var seasonCount = 0
        for (sid in subjectIds) {
            val seasons = dataOf("/wefeed-mobile-bff/subject-api/season-info?subjectId=$sid")
                ?.optJSONArray("seasons")?.objects() ?: continue
            seasonCount = maxOf(seasonCount, seasons.size)
            for (season in seasons) {
                val se = season.optInt("se", 1)
                val maxEp = season.optInt("maxEp", 0)
                for (ep in 1..maxEp) {
                    val key = "$sid|$se|$ep"
                    if (!seen.add(key)) continue
                    episodes += newEpisode(key) {
                        this.season = se
                        this.episode = ep
                        this.name = "S$se E$ep"
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            val res = dataOf("/wefeed-mobile-bff/subject-api/resource?subjectId=$id&se=1&ep=1")
            val list = res?.optJSONArray("list")?.objects() ?: emptyList()
            for (item in list) {
                val rawEp = item.optInt("episode", item.optInt("ep", 0))
                if (rawEp <= 0) continue
                val se = if (rawEp >= 10000) rawEp / 10000 else if (rawEp >= 100) rawEp / 100 else 1
                val ep = if (rawEp >= 10000) rawEp % 10000 else if (rawEp >= 100) rawEp % 100 else rawEp
                val key = "$id|$se|$ep"
                if (!seen.add(key)) continue
                episodes += newEpisode(key) { this.season = se; this.episode = ep; this.name = "S$se E$ep" }
            }
            if (episodes.isEmpty()) {
                episodes += newEpisode("$id|1|1") { this.season = 1; this.episode = 1; this.name = "Episode 1" }
            }
        }

        return newTvSeriesLoadResponse(title, "$id|0|0", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = score
            this.actors = actors
        }
    }

    private fun parseDuration(value: String): Int? {
        if (value.isBlank()) return null
        Regex("(\\d+)h").find(value)?.let { hours ->
            val minutes = Regex("(\\d+)m").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return hours.groupValues[1].toInt() * 60 + minutes
        }
        return Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isPlayableUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val u = url.lowercase()
        // ponytail: drop known placeholder/ad clips; extend if log shows other markers
        return !u.contains("deprecation") && !u.contains("placeholder") && !u.contains("anti_piracy") && !u.contains("antipiracy") && !u.contains("/ad/")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        val id = parts[0]
        val season = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: 0
        var found = false

        suspend fun collectFromPlayInfo(sid: String, se: Int, ep: Int) {
            val play = dataOf("/wefeed-mobile-bff/subject-api/play-info?subjectId=$sid&se=$se&ep=$ep") ?: return
            val streams = play.optJSONArray("streams")?.objects()
                ?: play.optJSONObject("data")?.optJSONArray("streams")?.objects() ?: emptyList()
            streams.forEach { stream ->
                val link = stream.optString("url").takeIf { isPlayableUrl(it) } ?: return@forEach
                val quality = stream.optString("resolutions")
                    .split(",").firstOrNull()?.trim()?.toIntOrNull() ?: 0
                callback(newSignedStream(link, quality, stream.optString("signCookie")))
                found = true
                val streamId = stream.optString("id")
                if (streamId.isNotBlank()) {
                    (dataOf("/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$sid&streamId=$streamId")
                        ?.optJSONArray("extCaptions")?.objects()
                        ?: dataOf("/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$sid&resourceId=$streamId")
                            ?.optJSONArray("extCaptions")?.objects()
                        ?: dataOf("/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$sid&resourceId=$streamId&episode=$ep")
                            ?.optJSONArray("extCaptions")?.objects()
                        ?: emptyList()).forEach { captionCallback(it, subtitleCallback) }
                }
            }
        }

        // 1. Movies (and some series) expose ready-made files here.
        val detail = dataOf("/wefeed-mobile-bff/subject-api/get?subjectId=$id")
            ?: dataOf("/wefeed-mobile-bff/subject-api/get?subjectId=$id&detail=1")
        detail?.optJSONArray("resourceDetectors")?.objects()?.forEach { detector ->
            detector.optJSONArray("resolutionList")?.objects()?.forEach { res ->
                val resEpisode = res.optInt("episode", 0)
                val resSeason = res.optInt("se", 0)
                if (episode > 0 && resEpisode != 0 && resEpisode != episode) return@forEach
                if (episode > 0 && resSeason != 0 && resSeason != season) return@forEach
                val link = res.optString("resourceLink").takeIf { isPlayableUrl(it) }
                    ?: res.optString("url").takeIf { isPlayableUrl(it) } ?: return@forEach
                callback(newVideoLink(link, res.optInt("resolution", detector.optInt("resolution", 0))))
                found = true
            }
            val direct = detector.optString("downloadUrl").takeIf { isPlayableUrl(it) }
            if (direct != null) {
                callback(newVideoLink(direct, 0))
                found = true
            }
        }

        // 2. Per-episode resources.
        val resourceSids = linkedSetOf(id)
        detail?.optJSONArray("dubs")?.objects()?.forEach { dub ->
            dub.optString("subjectId").takeIf { it.isNotBlank() }?.let { resourceSids += it }
        }
        for (sid in resourceSids) {
            val list = dataOf("/wefeed-mobile-bff/subject-api/resource?subjectId=$sid&se=$season&ep=$episode")
                ?.optJSONArray("list")?.objects() ?: emptyList()
            val wanted = listOf(season * 10000 + episode, season * 100 + episode)
            val chosen = list.filter { it.optInt("episode", 0) in wanted }.ifEmpty { list }
            chosen.forEach { item ->
                val link = item.optString("resourceLink").takeIf { isPlayableUrl(it) }
                    ?: item.optString("url").takeIf { isPlayableUrl(it) } ?: return@forEach
                callback(newVideoLink(link, item.optInt("resolution", 0)))
                found = true
                item.optJSONArray("extCaptions")?.objects()?.forEach { captionCallback(it, subtitleCallback) }
            }
        }

        // 3. Signed streams (dubs/lang variants included).
        for (sid in resourceSids) {
            collectFromPlayInfo(sid, season, episode)
            if (episode > 0 && !found) collectFromPlayInfo(sid, 0, 0)
        }

        return found
    }

    private suspend fun captionCallback(caption: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = caption.optString("url").takeIf { it.startsWith("http") } ?: return
        val lang = caption.optString("language")
            .ifBlank { caption.optString("lanName") }
            .ifBlank { caption.optString("lan") }
            .ifBlank { "Unknown" }
        subtitleCallback(newSubtitleFile(url, lang))
    }

    private suspend fun newVideoLink(url: String, quality: Int) = newExtractorLink(
        source = name,
        name = "$name ${if (quality > 0) "${quality}p" else "auto"}",
        url = url,
        type = ExtractorLinkType.VIDEO,
    ) {
        this.headers = mapOf("Referer" to mainUrl)
        this.quality = if (quality > 0) quality else Qualities.Unknown.value
    }

    private suspend fun newSignedStream(url: String, quality: Int, signCookie: String) = newExtractorLink(
        source = name,
        name = "$name stream ${if (quality > 0) "${quality}p" else ""}".trim(),
        url = url,
        type = if (url.contains(".mpd")) ExtractorLinkType.DASH
        else if (url.contains(".m3u8")) ExtractorLinkType.M3U8
        else if (url.contains(".mp4") || url.contains(".mkv")) ExtractorLinkType.VIDEO
        else INFER_TYPE,
    ) {
        this.quality = if (quality > 0) quality else Qualities.Unknown.value
        if (signCookie.isNotBlank()) this.headers = mapOf("Cookie" to signCookie)
    }
}
