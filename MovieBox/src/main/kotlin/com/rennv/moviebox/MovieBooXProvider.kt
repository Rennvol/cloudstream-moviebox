package com.rennv.moviebox

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
 * MovieBooX (moviebox.ph) provider.
 *
 * Talks to the mobile BFF (wefeed-mobile-bff) which every request signs:
 *  - x-client-token : "<ms>,<md5(reversed ms)>"
 *  - x-tr-signature : "<ms>|2|<base64 hmac-md5(canonical)>"
 *  - x-client-info  : device fingerprint json
 * An anonymous bearer token comes back in the `x-user` response header and is
 * required by search / play-info / resource.
 */
class MovieBooXProvider : MainAPI() {

    companion object {
        private const val WEB_HOST = "https://movie-box.co"
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

    override var name = "MovieBooX"
    override var mainUrl = HOSTS[0]
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "ranking:4809349160627587984" to "Trending",
        "list:1" to "Movies",
        "list:2" to "Series",
        "ranking:4380734070238626200" to "K-Drama",
        "ranking:5283462032510044280" to "Indo Drama",
        "ranking:8624142774394406504" to "C-Drama",
        "ranking:8617025562613270856" to "Anime",
        "ranking:5307082080063488480" to "Western",
        "ranking:5243634416492162328" to "Thai-Drama",
        "ranking:5692654647810388816" to "J-Drama",
        "ranking:5166486432344441960" to "Malay Drama",
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
            if (response.code !in listOf(403, 407, 429, 500, 502, 503, 504)) break
        }
        throw Exception("MovieBooX request failed: $lastError")
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

    // Series streams live on the web H5 API, not on the signed mobile API.
    private suspend fun webPlay(sid: String, se: Int, ep: Int, detailPath: String): JSONObject? {
        if (detailPath.isBlank()) return null
        val q = "subjectId=$sid&se=$se&ep=$ep&detailPath=$detailPath" +
            "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
        val response = try {
            app.get("$WEB_HOST/wefeed-h5api-bff/subject/play?$q", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36",
                "Origin" to WEB_HOST,
                "Referer" to "$WEB_HOST/movies/$detailPath?id=$sid&type=/movie/detail&detailSe=&detailEp=&lang=en",
                "Accept" to "application/json",
                "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                "x-request-lang" to "en",
                "x-vip-restrict" to "0",
                "x-no-high-risk-restrict" to "0",
                "x-source" to "",
            ))
        } catch (_: Exception) { return null }
        if (response.code != 200) return null
        return try { JSONObject(response.body?.string().orEmpty()).optJSONObject("data") }
        catch (_: Exception) { null }
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

    private val westernCountries = setOf(
        "united states", "usa", "united kingdom", "uk", "canada", "australia", "new zealand",
        "france", "germany", "spain", "italy", "ireland", "sweden", "norway", "denmark", "netherlands", "belgium", "austria", "switzerland", "poland", "portugal", "finland", "greece", "czech", "romania", "hungary", "iceland", "russia", "ukraine"
    )

    private fun JSONObject.allowedContent(strictWestern: Boolean = false): Boolean {
        val title = optString("title").ifBlank { optString("name") }.trim()
        if (title.isBlank()) return false
        val country = optString("countryName").trim().lowercase()
        val genre = optString("genre").lowercase()
        val hay = "$title $country $genre"
        val indiaJunk = listOf("india","pakistan","bangladesh","nepal","sri lanka","bollywood","hindi","tamil","telugu","malayalam","kannada","punjabi","bhojpuri","marathi","gujarati")
        if (indiaJunk.any { hay.contains(it) }) return false
        if (country.isBlank()) return true // keep unknowns, only block india signals above
        if (strictWestern) return westernCountries.any { country == it || country.contains(it) }
        return true
    }

    private fun JSONObject.toSearchResponse(onlyType: TvType? = null, filterContent: Boolean = true, strictWestern: Boolean = false): SearchResponse? {
        val id = optString("subjectId").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").ifBlank { optString("name") }.trim().takeIf { it.isNotBlank() } ?: return null
        if (filterContent && !allowedContent(strictWestern)) return null
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
        val strictWestern = kind == "ranking" && value == "5307082080063488480"
        val raw = when (kind) {
            "ranking" -> subjectsOf(
                dataOf("/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=$value&page=$page&perPage=20")
            )
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
        val list = sorted.mapNotNull { it.toSearchResponse(onlyType, filterContent = true, strictWestern = strictWestern) }

        if (list.isEmpty() && page == 1) {
            val fb = subjectsOf(dataOf("/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4809349160627587984&page=1&perPage=30"))
                .mapNotNull { it.toSearchResponse(onlyType, filterContent = false, strictWestern = false) }
            return newHomePageResponse(listOf(HomePageList(request.name, fb)))
        }
        return newHomePageResponse(listOf(HomePageList(request.name, list)))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val body = JSONObject()
            .put("page", page).put("perPage", 20).put("keyword", query)
            .toString()
        val list = subjectsOf(dataOf("/wefeed-mobile-bff/subject-api/search/v2", "POST", body))
            .mapNotNull { it.toSearchResponse(filterContent = true) }
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

        val baseTitle = titleRaw.substringBefore("[").trim().ifBlank { "MovieBooX $id" }
        val title = "$baseTitle [$sid0]"
        val description = data.optString("description")
        val plot = listOf("subjectId: $sid0", description.takeIf { it.isNotBlank() }).filterNotNull().joinToString("\n\n")
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
                this.plot = plot
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
                    episodes += newEpisode(key, {
                        this.season = se
                        this.episode = ep
                        this.name = "S$se E$ep"
                        this.description = "subjectId=$sid se=$se ep=$ep\nweb: fmoviesunblocked.net/spa/videoPlayPage/movies/$id?se=$se&ep=$ep"
                    }, false)
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
                episodes += newEpisode(key, { this.season = se; this.episode = ep; this.name = "S$se E$ep"; this.description = "subjectId=$id se=$se ep=$ep\nweb: fmoviesunblocked.net/spa/videoPlayPage/movies/$id?se=$se&ep=$ep" }, false)
            }
            if (episodes.isEmpty()) {
                episodes += newEpisode("$id|1|1", { this.season = 1; this.episode = 1; this.name = "Episode 1"; this.description = "subjectId=$id se=1 ep=1\nweb: fmoviesunblocked.net/spa/videoPlayPage/movies/$id?se=1&ep=1" }, false)
            }
        }

        return newTvSeriesLoadResponse(title, "$id|0|0", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
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
        return !u.contains("deprecation") && !u.contains("placeholder") && !u.contains("anti_piracy") && !u.contains("antipiracy") && !u.contains("/ad/") &&
            !u.contains("macdn.aoneroom.com/other/") // ponytail: site 23s bumper; extend when CDN path changes
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
        val detailPath = detail?.optString("detailUrl").orEmpty().substringAfterLast("/")
        if (season > 0 && episode > 0 && detailPath.isNotBlank()) {
            webPlay(id, season, episode, detailPath)?.optJSONArray("streams")?.objects()?.forEach { stream ->
                val link = stream.optString("url").takeIf { isPlayableUrl(it) } ?: return@forEach
                stream.optString("resolutions").split(",")
                    .mapNotNull { it.trim().toIntOrNull()?.takeIf { q -> q > 0 } }
                    .ifEmpty { listOf(0) }
                    // series H5 CDN needs movie-box.co Referer; api6 mobile CDN rejects it
                    .forEach { q -> callback(newVideoLink(link, q, mapOf("Referer" to "$WEB_HOST/"))) }
                found = true
            }
        }

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
        subtitleCallback(newSubtitleFile(lang, url))
    }

    private suspend fun newVideoLink(url: String, quality: Int, headers: Map<String, String> = emptyMap()) = newExtractorLink(
        source = name,
        name = "$name ${if (quality > 0) "${quality}p" else "auto"}",
        url = url,
        type = ExtractorLinkType.VIDEO,
    ) {
        // api6 CDN 429s with Referer; H5 series CDN requires movie-box.co Referer.
        this.quality = if (quality > 0) quality else Qualities.Unknown.value
        if (headers.isNotEmpty()) this.headers = headers
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
