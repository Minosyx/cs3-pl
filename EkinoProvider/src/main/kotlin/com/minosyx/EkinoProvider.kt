package com.minosyx

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.capitalizeString
import com.lagradost.cloudstream3.capitalizeStringNullable
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.select.Elements

class EkinoProvider : MainAPI() { // All providers must be an instance of MainAPI
    override var mainUrl = "https://ekino-tv.pl"
    override var name = "Ekino"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "pl"

    override val hasMainPage = true

    private val imagePrefix = "https:"
    private val videoPrefix = "$mainUrl/watch/f"
    private val interceptor = CloudflareKiller()

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/qf/?q=$query"
        val document = app.get(url, interceptor = interceptor, timeout = 30).document
        val lists = document.select(".movie-wrap > :not(div.menu-wrap)")
        val movies = lists[0].select(".movies-list-item")
        val series = lists[1].select(".movies-list-item")

        if (movies.isEmpty() && series.isEmpty()) return ArrayList()
        return getVideos(TvType.Movie, movies) + getVideos(TvType.TvSeries, series)
    }

    private fun getVideos(
        type: TvType,
        items: Elements,
    ): List<SearchResponse> {
        return items.mapNotNull { i ->
            var href = i.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            var img = i.selectFirst("a > img[src]")?.attr("src")
            val name = i.selectFirst(".title")?.text() ?: return@mapNotNull null
            if (href.isNotEmpty()) href = mainUrl + href
            if (img != null) {
                img = mainUrl + img
                img = img.replace("/thumbs/", "/normal/")
            }
            val year =
                i
                    .select(".cates")
                    .text()
                    .takeUnless { it.isBlank() }
                    ?.toIntOrNull()
            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(
                    name,
                    href,
                    TvType.TvSeries,
                ) {
                    this.year = year
                    this.posterUrl = img
                }
            } else {
                newMovieSearchResponse(
                    name,
                    href,
                    TvType.Movie,
                ) {
                    this.year = year
                    this.posterUrl = img
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val document = app.get(mainUrl, interceptor = interceptor, timeout = 30).document
        val lists = document.select(".mostPopular")
        val categories = ArrayList<HomePageList>()
        for (l in lists) {
            var title =
                capitalizeString(
                    l
                        .select("h4")
                        .text()
                        .lowercase()
                        .trim(),
                )
            val subtitle =
                capitalizeStringNullable(
                    l
                        .select(".sm")
                        .text()
                        .lowercase()
                        .trim(),
                )
            if (subtitle != null) title += " $subtitle"
            val isSeries = title.contains("serial", ignoreCase = true)
            val items =
                l.select("li").map { i ->
                    val leftScope = i.select(".scope_left")
                    val rightScope = i.select(".scope_right")

                    val name = rightScope.select(".title > a").text()
                    val href = mainUrl + leftScope.select("a").attr("href")
                    val poster = imagePrefix + leftScope.select("img[src]").attr("src").replace("/thumb/", "/normal/")
                    val year =
                        rightScope
                            .select(".cates")
                            .text()
                            .takeUnless { it.isBlank() }
                            ?.toIntOrNull()

                    if (isSeries) {
                        newTvSeriesSearchResponse(name, href) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(name, href) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
            categories.add(HomePageList(title, items))
        }
        return newHomePageResponse(categories)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor, timeout = 30).document
        val documentTitle = document.select("title").text().trim()

        if (documentTitle.startsWith("Logowanie")) {
            throw RuntimeException("This site requires login to view content")
        }

        val title = document.select("h1.title").text()
        val data = document.select(".playerContainer").outerHtml()
        val posterUrl = mainUrl + document.select(".moviePoster").attr("src")
        val year =
            document
                .select(".catBox .cat .a")
                .text()
                .toIntOrNull()
        val plot = document.select(".descriptionMovie").text()
        val episodesElements = document.select(".list-series a[href]")
        if (episodesElements.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.name = name
            }
        }
        val episodes =
            episodesElements
                .mapNotNull { episode ->
                    val e = episode.text()
                    val regex = Regex("""\[(\d+)\]""").findAll(episode.attr("href"))
                    val seasonEpisodeList = regex.map { it.groups[1]?.value }.toList()
                    newEpisode(
                        mainUrl + episode.attr("href"),
                    ) {
                        this.season = seasonEpisodeList.getOrNull(0)?.toIntOrNull()
                        this.episode = seasonEpisodeList.getOrNull(1)?.toIntOrNull()
                        this.name = e.trim()
                    }
                }.toMutableList()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes,
        ) {
            this.name = name
            this.plot = plot
            this.year = year
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0"
        val headers =
            mapOf(
                "User-Agent" to userAgent,
            )
        val document =
            if (data.startsWith("http")) {
                app
                    .get(data, interceptor = interceptor, timeout = 30)
                    .document
            } else {
                Jsoup.parse(data)
            }

        val tag = "MINOSYX"
        Log.d(tag, "INCOMING DATA LINK IS: " + data)

        document.select(".playerContainer .tab-content div[role]").map { item ->
            val id = item.id()
            val player = id.substringAfterLast("-")
            val code = id.substringBeforeLast("-")
            Log.d(player, "REQUESTED PLAYER IS: " + "$videoPrefix/$player/$code")
            val frameDocument = app.get("$videoPrefix/$player/$code", headers, data, interceptor = interceptor, timeout = 30).document
            val link = frameDocument.select("a.buttonprch").attr("href")
            Log.d(player, "LINK TO PAGE IS: " + link)
            val videoDocument = app.get("$link", headers, "$videoPrefix/$player/$code", interceptor = interceptor, timeout = 30).document
            val videoLink = videoDocument.selectFirst("iframe[src]")?.attr("src") ?: link
            Log.d(player, "OBTAINED IFRAM IS: " + videoLink)
            callback.invoke(
                newExtractorLink(player, player, videoLink, ExtractorLinkType.M3U8) {
                    this.referer = "$videoPrefix/$player/$code"
                },
            )
        }
        return true
    }
}
