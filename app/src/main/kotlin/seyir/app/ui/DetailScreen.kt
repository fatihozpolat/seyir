package seyir.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.LoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import seyir.app.AppController
import seyir.app.LoadedDetail
import seyir.app.PlayContext
import seyir.app.Screen
import seyir.app.UiEpisodeLabels
import seyir.app.userMessage
import seyir.app.data.Bookmark
import seyir.app.data.WatchEntry
import seyir.app.i18n.I18n
import seyir.app.i18n.LocalStrings
import seyir.app.tmdb.TmdbInfo
import seyir.app.tmdb.TmdbQuery
import seyir.app.tmdb.mergeDetail
import seyir.app.tmdb.yearInName
import seyir.core.Playable
import seyir.core.playables

private sealed interface DetailState {
    data object Loading : DetailState
    data class Failed(val message: String) : DetailState
    data class Loaded(val response: LoadResponse, val items: List<Playable>) : DetailState
}

@Composable
fun DetailScreen(controller: AppController, screen: Screen.Detail) {
    val t = LocalStrings.current
    val api = controller.provider(screen.provider)
    var state by remember(screen) { mutableStateOf<DetailState>(DetailState.Loading) }
    var attempt by remember(screen) { mutableStateOf(0) }

    LaunchedEffect(screen, attempt) {
        if (api == null) {
            state = DetailState.Failed(t.providerNotLoaded(screen.provider))
            return@LaunchedEffect
        }
        state = DetailState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { api.load(screen.url) }.fold(
                onSuccess = { response ->
                    if (response == null) DetailState.Failed(t.providerEmptyResponse)
                    else DetailState.Loaded(response, response.playables(UiEpisodeLabels(t)))
                },
                onFailure = { DetailState.Failed(userMessage(it, t)) },
            )
        }
    }

    when (val s = state) {
        DetailState.Loading -> Box(Modifier.fillMaxSize()) {
            Backdrop(screen.posterUrl, blurred = true)
            CenteredLoading(t.opening(screen.name))
        }
        is DetailState.Failed -> ErrorPanel(t.detailsFailed(s.message), onRetry = { attempt++ })
        is DetailState.Loaded -> DetailContent(controller, screen, s.response, s.items) { item ->
            controller.linksRequest = PlayContext(
                provider = screen.provider,
                loadUrl = screen.url,
                showName = s.response.name,
                posterUrl = s.response.posterUrl ?: screen.posterUrl,
                item = item,
                siblings = s.items,
            )
        }
    }

    // Geliştirici senaryosu (DevScript) açık detayın öğelerine buradan ulaşır
    LaunchedEffect(state) {
        controller.openDetail = (state as? DetailState.Loaded)?.let { loaded ->
            LoadedDetail(screen, loaded.response.name, loaded.response.posterUrl ?: screen.posterUrl, loaded.items)
        }
    }
}

@Composable
private fun DetailContent(
    controller: AppController,
    screen: Screen.Detail,
    response: LoadResponse,
    items: List<Playable>,
    onPlay: (Playable) -> Unit,
) {
    val t = LocalStrings.current
    // libraryVersion okunarak geçmiş değişince ilerleme çubukları yenilenir
    val version = controller.libraryVersion
    val progress = remember(version, screen) { controller.library.entriesFor(screen.provider, screen.url) }
    val seasons = remember(items) { items.mapNotNull { it.season }.distinct().sorted() }
    var season by remember(items) {
        mutableStateOf(progress.values.maxByOrNull { it.updatedAt }?.season ?: seasons.firstOrNull())
    }
    val resume = remember(progress, items) { resumeTarget(items, progress) }
    val listState = rememberLazyListState()

    // Satırdaki tik: izlenmemişi "izlendi" yapar, izlenmişin kaydını siler (ilerleme de sıfırlanır)
    fun toggleWatched(item: Playable) {
        val entry = progress[item.data]
        if (entry?.finished == true) {
            controller.library.removeHistory(entry.key)
        } else {
            controller.library.record(
                WatchEntry(
                    provider = screen.provider,
                    loadUrl = screen.url,
                    showName = response.name,
                    posterUrl = response.posterUrl ?: screen.posterUrl,
                    itemTitle = item.title,
                    itemData = item.data,
                    season = item.season,
                    episode = item.episode,
                    position = entry?.duration ?: entry?.position ?: 0.0,
                    duration = entry?.duration,
                    finished = true,
                )
            )
        }
        controller.libraryChanged()
    }

    // İsteğe bağlı TMDB zenginleştirmesi: anahtar yoksa hiç çalışmaz; ağ işi arka planda yapılır,
    // sonuç gelene dek ekran eklentinin verdiğiyle görünür
    val tmdbKey = controller.settings.tmdbApiKey
    val tmdb by produceState<TmdbInfo?>(null, response, tmdbKey) {
        value = if (tmdbKey.isBlank()) null
        else controller.tmdb.lookup(response.name, response.year ?: yearInName(response.name), response.type.name, items.size)
    }
    val recommendations = remember(response) { response.recommendations.orEmpty().distinctBy { it.url } }
    val posterFallbacks = remember(response, tmdbKey) { mutableStateMapOf<String, String>() }
    LaunchedEffect(recommendations, tmdbKey) {
        if (tmdbKey.isBlank()) return@LaunchedEffect
        val missing = recommendations.filter { it.posterUrl.isNullOrBlank() }.map { TmdbQuery(it.url, it.name, it.type?.name) }
        if (missing.isNotEmpty()) controller.tmdb.fillPosters(missing) { url, poster -> posterFallbacks[url] = poster }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "header") {
                DetailHeader(controller, screen, response, items, resume, progress, tmdb, onPlay)
            }

            if (items.size > 1) {
                item(key = "episodes-head") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.episodes, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Text("${items.size}", style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
                    }
                    if (seasons.size > 1) {
                        SeasonTabs(seasons, season) { season = it }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val visible = if (seasons.size > 1) items.filter { it.season == season } else items
                val withThumbs = visible.any { !it.posterUrl.isNullOrBlank() && it.posterUrl != response.posterUrl }
                // Eklentiler aynı bölüm adresini birden çok kez verebiliyor (ör. AnimeciX); anahtar
                // yalnız adres olursa Compose çakışan anahtarda çöküyor, bu yüzden sıra numarası eklenir
                itemsIndexed(visible, key = { index, item -> "$index|${item.data}" }) { index, item ->
                    EpisodeRow(item, index, progress[item.data], withThumbs, item == resume, onToggleWatched = { toggleWatched(item) }) { onPlay(item) }
                }
            }

            if (recommendations.isNotEmpty()) {
                item(key = "recommendations") {
                    Spacer(Modifier.height(12.dp))
                    PosterRow(
                        t.similarTitles,
                        recommendations,
                        posterFallbacks = posterFallbacks,
                        onPlay = { rec -> controller.quickPlay(Screen.Detail(screen.provider, rec.url, rec.name, rec.posterUrl)) },
                        busyUrl = controller.quickPlaying,
                    ) { rec ->
                        controller.navigator.open(Screen.Detail(screen.provider, rec.url, rec.name, rec.posterUrl))
                    }
                }
            }
            item { Spacer(Modifier.height(56.dp)) }
        }
        ScrollBar(listState)
    }
}

@Composable
private fun DetailHeader(
    controller: AppController,
    screen: Screen.Detail,
    response: LoadResponse,
    items: List<Playable>,
    resume: Playable?,
    progress: Map<String, WatchEntry>,
    tmdb: TmdbInfo?,
    onPlay: (Playable) -> Unit,
) {
    val t = LocalStrings.current
    val version = controller.libraryVersion
    var bookmarked by remember(version, screen) {
        mutableStateOf(controller.library.isBookmarked(screen.provider, screen.url))
    }
    var plotExpanded by remember(screen) { mutableStateOf(false) }
    val ownPoster = (response.posterUrl ?: screen.posterUrl)?.takeIf { it.isNotBlank() }
    val ownBackdrop = response.backgroundPosterUrl?.takeIf { it.isNotBlank() && it != ownPoster }
    // Eklentinin verdiği her alan korunur; TMDB yalnız eksikleri doldurur
    val merged = remember(response, screen, tmdb) {
        mergeDetail(ownPoster, ownBackdrop, cleanPlot(response.plot), response.year, response.score?.toDouble(10), tmdb)
    }
    val poster = merged.posterUrl
    val backdrop = merged.backdropUrl
    // Eklentiye ait başlıklar (Referer, çerez) TMDB görsellerine gönderilmez
    val posterHeaders = if (merged.posterFromTmdb) null else response.posterHeaders
    val backdropHeaders = if (backdrop != null) (if (merged.backdropFromTmdb) null else response.posterHeaders) else posterHeaders

    Box(Modifier.fillMaxWidth().heightIn(min = 520.dp)) {
        Backdrop(backdrop ?: poster, blurred = backdrop == null, headers = backdropHeaders, modifier = Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 112.dp, bottom = 36.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(
                poster,
                Modifier.width(220.dp).aspectRatio(2f / 3f)
                    .shadow(24.dp, PosterShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(PosterShape)
                    .border(1.dp, SeyirColors.hairline, PosterShape),
                posterHeaders,
            )
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f).widthIn(max = 760.dp)) {
                Text(displayTitle(response.name), style = MaterialTheme.typography.displaySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                MetaLine(
                    listOf(
                        typeLabel(response.type.name),
                        merged.year?.toString(),
                        merged.score?.let { "★ ${formatScore(it)}" + if (merged.scoreFromTmdb) " TMDB" else "" },
                        response.duration?.takeIf { it > 0 }?.let { t.minutesShort(it) },
                        if (items.size > 1) t.episodesCount(items.size) else null,
                        screen.provider,
                    )
                )
                val tags = response.tags.orEmpty().filter { it.isNotBlank() }.take(6)
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    MetaLine(tags, color = SeyirColors.textMuted)
                }
                val plot = merged.plot
                if (plot != null) {
                    Spacer(Modifier.height(16.dp))
                    val plotInteraction = remember { MutableInteractionSource() }
                    val plotShape = RoundedCornerShape(6.dp)
                    Text(
                        plot,
                        Modifier.widthIn(max = 680.dp).clip(plotShape)
                            .focusRing(plotInteraction, plotShape)
                            .clickable(plotInteraction, indication = null, role = Role.Button) { plotExpanded = !plotExpanded },
                        style = MaterialTheme.typography.bodyLarge,
                        color = SeyirColors.textSecondary,
                        maxLines = if (plotExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val actors = response.actors.orEmpty().map { it.actor.name }.filter { it.isNotBlank() }
                if (actors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t.castLabel + "  " + actors.take(8).joinToString(", "),
                        Modifier.widthIn(max = 680.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = SeyirColors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (resume != null) {
                        SeyirButton(
                            playLabel(resume, progress[resume.data], items.size > 1),
                            { onPlay(resume) },
                            kind = ButtonKind.Primary,
                            icon = Icons.Rounded.PlayArrow,
                            large = true,
                        )
                    }
                    SeyirButton(
                        if (bookmarked) t.inMyList else t.addToMyList,
                        {
                            bookmarked = controller.library.toggleBookmark(
                                Bookmark(
                                    provider = screen.provider,
                                    url = screen.url,
                                    name = response.name,
                                    posterUrl = poster,
                                    type = response.type.name,
                                )
                            )
                            controller.libraryChanged()
                        },
                        icon = if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        large = true,
                    )
                }
                if (items.isEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(t.noPlayableItems, color = SeyirColors.textMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Sezon sekmeleri: seçili sezonun altında kehribar çizgi. */
@Composable
private fun SeasonTabs(seasons: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    val t = LocalStrings.current
    TextTabs(seasons.map { t.seasonLabel(it) }, seasons.indexOf(selected)) { onSelect(seasons[it]) }
}

@Composable
private fun EpisodeRow(
    item: Playable,
    index: Int,
    entry: WatchEntry?,
    withThumb: Boolean,
    isNext: Boolean,
    onToggleWatched: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(if (hovered) SeyirColors.surface else Color.Transparent, tween(140))
    val finished = entry?.finished == true
    val partial = entry?.progress?.takeIf { !finished && it > 0.01f }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = PageGutter - 12.dp)
            .clip(shape)
            .background(background)
            .focusRing(interaction, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = episodeName(item)
        // Ad zaten "3. Bölüm" ise baştaki numara sütunu tekrar olur; yerine sıradaki için nokta kalır
        val numberedName = name.startsWith("${item.episode}.") || (item.episode != null && name == t.episodeNumbered(item.episode!!))
        if (!numberedName) {
            Text(
                "${item.episode ?: index + 1}",
                Modifier.width(36.dp),
                style = MaterialTheme.typography.titleMedium,
                color = if (isNext) SeyirColors.accent else SeyirColors.textMuted,
            )
        } else {
            Box(Modifier.width(36.dp)) {
                if (isNext) Box(Modifier.size(8.dp).clip(CircleShape).background(SeyirColors.accent))
            }
        }
        if (withThumb) {
            Box(Modifier.width(144.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                Poster(item.posterUrl, Modifier.fillMaxSize())
                if (hovered) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                if (partial != null) {
                    ProgressBar(partial, Modifier.align(Alignment.BottomCenter).padding(6.dp), Color.White.copy(alpha = 0.25f))
                }
            }
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (finished) SeyirColors.textSecondary else SeyirColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val code = if (item.season != null && item.episode != null && !name.contains(EPISODE_WORDS)) episodeCode(item) else null
            if (code != null || (partial != null && !withThumb)) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    code?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted) }
                    if (partial != null && !withThumb) {
                        if (code != null) Spacer(Modifier.width(12.dp))
                        ProgressBar(partial, Modifier.width(140.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        when {
            finished -> Text(t.watched, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
            entry != null && entry.position > 0 -> Text(
                formatTime(entry.position),
                style = MaterialTheme.typography.labelMedium,
                color = SeyirColors.textSecondary,
            )
            hovered && !withThumb -> Box(
                Modifier.size(32.dp).clip(CircleShape).background(SeyirColors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, t.play, tint = SeyirColors.onAccent, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        // İzlendi işareti: izlenmişte dolu tik, değilse satırın üzerine gelince belirginleşen boş tik
        QuietIconButton(
            if (finished) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline,
            if (finished) t.markUnwatched else t.markWatched,
            size = 36.dp,
            tint = when {
                finished -> SeyirColors.accent
                hovered -> SeyirColors.textSecondary
                else -> SeyirColors.textMuted.copy(alpha = 0.5f)
            },
            onClick = onToggleWatched,
        )
    }
}

/** Adında sezon/bölüm geçen bölümlerde ayrıca "S1 B3" yazılmaz. */
private val EPISODE_WORDS = Regex("(?i)sezon|bölüm|episode|season")
