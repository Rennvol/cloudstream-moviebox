package com.rennv.nontonwok

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
 * NontonWok (nonton.work) provider.
 *
 * This provider interacts with nonton.work website to fetch movie and series data.
 * It uses web scraping and API endpoints to retrieve content.
 */
class NontonWokProvider : MainAPI() {

    companion object {
        // The main website
        private const val HOST = "https://nonton.work"

        // Common user agent for Android device
        private const val MODEL = "22101316G"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; $MODEL Build/UD1A.230803.041) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.114 Mobile Safari/537.36"
    }

    override var name = "NontonWok"
    override var mainUrl = HOST
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "0" to "Trending",
        "1" to "Movies",
        "2" to "Series",
        "3" to "Animation",
    )

    // ----------------------------------------------------------------------
    // API endpoints and parsing logic
    // ----------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, value) = request.data.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val onlyType = when {
            kind == "list" && value == "1" -> TvType.Movie
            kind == "list" && value == "2" -> TvType.TvSeries
            else -> null
        }

        val raw = when (kind) {
            "list" -> {
                // For list endpoints (movies/series), scrape the HTML content
                when (value) {
                    "1" -> scrapeMovies(page)
                    "2" -> scrapeTvSeries(page)
                    "3" -> scrapeAnimation(page)
                    else -> scrapeTrending(page)
                }
            }
            else -> {
                // For other endpoints, use default trending
                scrapeTrending(page)
            }
        }

        val list = raw.mapNotNull { it.toSearchResponse(onlyType) }

        return newHomePageResponse(listOf(HomePageList(request.name, list)))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchResults = scrapeSearch(query, page)
        return searchResults.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Extract ID from URL (last segment)
        val id = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotEmpty() } ?: return newMovieLoadResponse("Not found", url, TvType.Movie)

        // Try to scrape movie/series details
        val details = scrapeDetails(id) ?: return newMovieLoadResponse("Not found", url, TvType.Movie)

        val title = details.optString("title", "")
        val description = details.optString("description", "")
        val poster = details.optString("poster", "")
        val releaseDate = details.optString("releaseDate", "")
        val year = releaseDate.take(4).toIntOrNull() ?: 0
        val duration = details.optString("duration", "").let { parseDuration(it) } ?: 0
        val genres = details.optJSONArray("genres")?.let { getGenres(it) } ?: listOf()

        val sid = details.optString("subjectId", id)
        val isSeries = details.optBoolean("isSeries", false)

        if (!isSeries) {
            return newMovieLoadResponse(title, "$id|0|0", TvType.Movie, "$id|0|0") {
                this.plot = description
                this.posterUrl = poster
                this.year = year
                this.tags = genres
                this.duration = duration
            }
        }

        // For series, we need to get episodes
        val episodes = scrapeEpisodes(sid, details) ?: listOf()

        return newTvSeriesLoadResponse(title, "$sid|0|0", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
        }
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

        // Try to scrape video links
        val links = scrapeVideoLinks(id, season, episode) ?: listOf()

        if (links.isEmpty()) {
            return false
        }

        links.forEach { link ->
            callback(newExtractorLink(link, 0))
        }

        return true
    }

    // ----------------------------------------------------------------------
    // Parsing functions
    // ----------------------------------------------------------------------

    private fun parseDuration(value: String): Int? {
        if (value.isBlank()) return null
        Regex("(\d+)h").find(value)?.let { hours ->
            val minutes = Regex("(\d+)m").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return hours.groupValues[1].toInt() * 60 + minutes
        }
        return Regex("(\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun getGenres(jsonArray: JSONArray): List<String> {
        val genres = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            jsonArray.optJSONObject(i)?.let { genre ->
                genres.add(genre.optString("name", ""))
            }
        }
        return genres
    }

    // ----------------------------------------------------------------------
    // Web scraping functions
    // ----------------------------------------------------------------------

    private suspend fun scrapeTrending(page: Int): List<JSONObject> {
        // This function scrapes trending movies/series from nonton.work
        // You'll need to implement the scraping logic here
        // For now, return an empty list
        return emptyList()
    }

    private suspend fun scrapeMovies(page: Int): List<JSONObject> {
        // This function scrapes movies from nonton.work/movies
        // You'll need to implement the scraping logic here
        // For now, return an empty list
        return emptyList()
    }

    private suspend fun scrapeTvSeries(page: Int): List<JSONObject> {
        // This function scrapes TV series from nonton.work/tv-series
        // You'll need to implement the scraping logic here
        // For now, return an empty list
        return emptyList()
    }

    private suspend fun scrapeAnimation(page: Int): List<JSONObject> {
        // This function scrapes animation from nonton.work/animation
        // You'll need to implement the scraping logic here
        // For now, return an empty list
        return emptyList()
    }

    private suspend fun scrapeSearch(query: String, page: Int): List<JSONObject> {
        // This function scrapes search results from nonton.work/search?q=...
        // You'll need to implement the scraping logic here
        // For now, return an empty list
        return emptyList()
    }

    private suspend fun scrapeDetails(id: String): JSONObject? {
        // This function scrapes movie/series details from nonton.work/watch/$id
        // You'll need to implement the scraping logic here
        // For now, return null
        return null
    }

    private suspend fun scrapeEpisodes(sid: String, details: JSONObject): List<Episode>? {
        // This function scrapes episodes for a series
        // You'll need to implement the scraping logic here
        // For now, return null
        return null
    }

    private suspend fun scrapeVideoLinks(id: String, season: Int, episode: Int): List<String>? {
        // This function scrapes video links for a movie/episode
        // You'll need to implement the scraping logic here
        // For now, return null
        return null
    }
}