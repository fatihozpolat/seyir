package seyir.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import seyir.app.AppController
import seyir.app.HealthSource
import seyir.app.userMessage
import seyir.app.PlayContext
import seyir.app.Screen
import seyir.core.withHardTimeout
import seyir.app.i18n.I18n
import seyir.app.i18n.LocalStrings
import seyir.core.playables

/** Bir sağlayıcının yüklenmiş ana sayfası. */
class HomeFeed {
    val rows = mutableStateListOf<HomePageList>()
    var loading by mutableStateOf(false)
    var done by mutableStateOf(false)
    val errors = mutableStateListOf<String>()

    /** Panoda gösterilen yapımların ayrıntıları (adres → yanıt; yüklenemediyse null). */
    val details = mutableStateMapOf<String, LoadResponse?>()
    var heroIndex by mutableIntStateOf(0)
}

/** Ana sayfa önbelleği: sağlayıcı adı → yüklenmiş şeritler. */
class HomeState {
    // Düz HashMap: kompozisyon sırasında eklemek gözlemlenen durumu değiştirmesin
    val feeds = HashMap<String, HomeFeed>()
}

/** Ayarlarda seçim yoksa ana sayfada ilk denenecek, çalıştığı doğrulanmış sağlayıcılar. */

/** Panoda dönen yapım sayısı ve her birinin ekranda kalma süresi. */
private const val HERO_COUNT = 5
private const val HERO_INTERVAL_MS = 9_000L

/** Ana sayfa şeritlerinin aynı anda en çok kaç istekle yükleneceği ve her isteğin süre sınırı. */
private const val HOME_CONCURRENCY = 3
private const val HOME_TIMEOUT_MS = 25_000L

@Composable
fun HomeScreen(controller: AppController) {
    val t = LocalStrings.current
    val candidates = controller.enabledProviders.filter { it.hasMainPage }
    if (controller.providers.isEmpty()) {
        Welcome(controller)
        return
    }
    if (candidates.isEmpty()) {
        EmptyPanel(t.noHomeProviders, t.noHomeProvidersHint, Icons.Rounded.Tv)
        return
    }
    val selected = candidates.firstOrNull { it.name == controller.settings.homeProvider }
        ?: candidates.first()
    val feed = controller.home.feeds.getOrPut(selected.name) { HomeFeed() }
    val scope = controller.scope

    fun load(force: Boolean) {
        if (feed.loading || (feed.done && !force)) return
        feed.rows.clear()
        feed.errors.clear()
        feed.details.clear()
        feed.heroIndex = 0
        feed.loading = true
        scope.launch {
            // Sayfalar en çok HOME_CONCURRENCY'lik gruplar halinde paralel yüklenir; her şerit gelir gelmez
            // gösterilir, ama sıra sayfa sırasında kalır (sayfa i'nin şeritleri, önceki sayfaların sonrasına eklenir)
            val pages = selected.mainPage
            val arrived = arrayOfNulls<List<HomePageList>>(pages.size)
            val gate = Semaphore(HOME_CONCURRENCY)
            var failure: Throwable? = null
            coroutineScope {
                pages.forEachIndexed { index, page ->
                    launch {
                        val result = try {
                            Result.success(gate.withPermit {
                                val request = controller.domains.rewrite(
                                    selected, MainPageRequest(page.name, page.data, page.horizontalImages)
                                )
                                withHardTimeout(scope, HOME_TIMEOUT_MS) { selected.getMainPage(1, request) }
                            })
                        } catch (e: TimeoutCancellationException) {
                            Result.failure(IllegalStateException(t.requestTimedOut((HOME_TIMEOUT_MS / 1000).toInt())))
                        } catch (e: CancellationException) {
                            coroutineContext.ensureActive()
                            Result.failure(e)
                        } catch (e: Throwable) {
                            // Eklentiden gelen Error'lar (NoSuchMethodError vb.) da hata sonucu olur
                            Result.failure(e)
                        }
                        result.onSuccess { response ->
                            val rows = response?.items?.filter { it.list.isNotEmpty() }.orEmpty()
                            arrived[index] = rows
                            val position = (0 until index).sumOf { arrived[it]?.size ?: 0 }
                            feed.rows.addAll(position, rows)
                        }.onFailure {
                            if (failure == null) failure = it
                            feed.errors.add("${page.name}: ${it.message ?: it::class.simpleName}")
                        }
                    }
                }
            }
            feed.loading = false
            feed.done = true
            // Boş ana sayfa hata değildir (sağlayıcının ana sayfası olmayabilir); yalnız gerçek hata kırmızıya çeker
            if (feed.rows.isNotEmpty()) controller.health.report(selected.name, ok = true, source = HealthSource.HOME)
            else failure?.let { controller.health.report(selected.name, ok = false, message = userMessage(it, t), source = HealthSource.HOME) }
        }
    }

    LaunchedEffect(selected.name) { load(force = false) }

    val open = { item: SearchResponse ->
        controller.navigator.open(Screen.Detail(selected.name, item.url, item.name, item.posterUrl))
    }

    Box(Modifier.fillMaxSize()) {
        when {
            feed.rows.isEmpty() && feed.loading -> CenteredLoading(t.providerLoading(selected.name))
            feed.rows.isEmpty() && feed.done -> ErrorPanel(
                (feed.errors.firstOrNull()?.let { t.homeLoadFailed(it.take(160)) } ?: t.homeReturnedNothing) +
                    "\n" + t.homeFailureHint,
                onRetry = { load(force = true) },
            )
            else -> {
                val state = rememberLazyListState()
                val featured = remember(feed.rows.size) {
                    feed.rows.firstOrNull()?.list.orEmpty().distinctBy { it.url }.take(HERO_COUNT)
                }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    if (featured.isNotEmpty()) {
                        item(key = "hero") { HomeHero(controller, selected, feed, featured) }
                    }
                    item(key = "continue") { ContinueRow(controller) }
                    itemsIndexed(feed.rows, key = { index, row -> "$index|${row.name}" }) { _, row ->
                        PosterRow(
                            row.name,
                            row.list.distinctBy { it.url },
                            onPlay = { controller.quickPlay(Screen.Detail(selected.name, it.url, it.name, it.posterUrl)) },
                            busyUrl = controller.quickPlaying,
                            onOpen = open,
                        )
                    }
                    if (feed.loading) {
                        item {
                            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = SeyirColors.accent, strokeWidth = 2.dp)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
                ScrollBar(state)
            }
        }

        // Pano üzerinde duran araç çubuğu
        Row(
            Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.navHome, style = MaterialTheme.typography.titleSmall, color = SeyirColors.text)
            Spacer(Modifier.weight(1f))
            ProviderPicker(candidates, selected) { api -> controller.updateSettings { it.copy(homeProvider = api.name) } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (feed.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SeyirColors.accent)
                } else {
                    QuietIconButton(Icons.Rounded.Refresh, t.refresh, filled = true) { load(force = true) }
                }
            }
        }
    }
}

/** Öne çıkan pano: seçili sağlayıcının ilk sırasından birkaç yapım, kendiliğinden döner. */
@Composable
private fun HomeHero(controller: AppController, api: MainAPI, feed: HomeFeed, featured: List<SearchResponse>) {
    val t = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val index = feed.heroIndex.coerceIn(0, featured.lastIndex)
    val item = featured[index]

    // Fare panodayken durur; yoksa sıradakine geçer
    LaunchedEffect(index, hovered, featured.size) {
        if (hovered || featured.size < 2) return@LaunchedEffect
        delay(HERO_INTERVAL_MS)
        feed.heroIndex = (index + 1) % featured.size
    }
    // Özet, yıl ve arka plan görseli için ayrıntı bir kez yüklenir
    LaunchedEffect(item.url) {
        if (feed.details.containsKey(item.url)) return@LaunchedEffect
        val response = withContext(Dispatchers.IO) { runCatching { api.load(item.url) }.getOrNull() }
        feed.details[item.url] = response
    }

    Box(Modifier.fillMaxWidth().height(424.dp).hoverable(interaction)) {
        Crossfade(item, animationSpec = tween(700)) { shown ->
            val detail = feed.details[shown.url]
            HeroArt(shown, detail)
        }
        val detail = feed.details[item.url]
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = PageGutter, bottom = 36.dp).widthIn(max = 620.dp)
        ) {
            Text(
                displayTitle(detail?.name ?: item.name),
                style = MaterialTheme.typography.displayLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            MetaLine(
                listOf(
                    typeLabel((detail?.type ?: item.type)?.name),
                    detail?.year?.toString(),
                    detail?.score?.let { "★ ${formatScore(it.toDouble(10))}" },
                    detail?.duration?.takeIf { it > 0 }?.let { t.minutesShort(it) },
                    api.name,
                )
            )
            val plot = cleanPlot(detail?.plot)
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    plot,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SeyirColors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroPlayButton(controller, api, item, detail)
                SeyirButton(t.details, {
                    controller.navigator.open(Screen.Detail(api.name, item.url, item.name, item.posterUrl))
                }, icon = Icons.Outlined.Info, large = true)
            }
        }
        if (featured.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = PageGutter, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                featured.indices.forEach { i -> HeroDot(i, i == index) { feed.heroIndex = i } }
            }
        }
    }
}

/** Pano görseli: yatay arka plan varsa o; yoksa bulanık afiş zemini ve sağda keskin afiş. */
@Composable
private fun HeroArt(item: SearchResponse, detail: LoadResponse?) {
    val backdrop = detail?.backgroundPosterUrl?.takeIf { it.isNotBlank() && it != (detail.posterUrl ?: item.posterUrl) }
    val poster = detail?.posterUrl ?: item.posterUrl
    Box(Modifier.fillMaxSize()) {
        Backdrop(backdrop ?: poster, blurred = backdrop == null, headers = detail?.posterHeaders ?: item.posterHeaders)
        if (backdrop == null && !poster.isNullOrBlank()) {
            Poster(
                poster,
                Modifier.align(Alignment.TopEnd).padding(end = PageGutter, top = 76.dp).height(300.dp).aspectRatio(2f / 3f)
                    .shadow(28.dp, PosterShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(PosterShape)
                    .border(1.dp, SeyirColors.hairline, PosterShape),
                detail?.posterHeaders ?: item.posterHeaders,
            )
        }
    }
}

@Composable
private fun HeroPlayButton(controller: AppController, api: MainAPI, item: SearchResponse, detail: LoadResponse?) {
    val version = controller.libraryVersion
    val strings = LocalStrings.current
    val items = remember(detail, strings) { detail?.playables(seyir.app.UiEpisodeLabels(strings)).orEmpty() }
    val progress = remember(version, item.url) { controller.library.entriesFor(api.name, item.url) }
    val target = remember(items, progress) { resumeTarget(items, progress) }
    if (detail == null || target == null) {
        // Ayrıntı yüklenmeden ne oynatılacağı bilinmez; detay sayfasına götürür
        SeyirButton(LocalStrings.current.watch, {
            controller.navigator.open(Screen.Detail(api.name, item.url, item.name, item.posterUrl))
        }, kind = ButtonKind.Primary, icon = Icons.Rounded.PlayArrow, large = true)
        return
    }
    SeyirButton(
        playLabel(target, progress[target.data], items.size > 1),
        {
            controller.linksRequest = PlayContext(
                provider = api.name,
                loadUrl = item.url,
                showName = detail.name,
                posterUrl = detail.posterUrl ?: item.posterUrl,
                item = target,
            )
        },
        kind = ButtonKind.Primary,
        icon = Icons.Rounded.PlayArrow,
        large = true,
    )
}

@Composable
private fun HeroDot(index: Int, active: Boolean, onClick: () -> Unit) {
    val width by animateDpAsState(if (active) 22.dp else 8.dp, tween(300))
    val color by animateColorAsState(if (active) SeyirColors.text else Color.White.copy(alpha = 0.3f), tween(300))
    val interaction = remember { MutableInteractionSource() }
    val description = LocalStrings.current.heroSlide(index + 1)
    Box(
        Modifier.size(width, 8.dp).clip(CircleShape).background(color)
            .focusRing(interaction, CircleShape, 2.dp)
            .clickable(interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description; selected = active }
    )
}

/** Bütün sağlayıcılardaki yarım kalan yapımlar, en son izlenen başta. */
@Composable
private fun ContinueRow(controller: AppController) {
    val version = controller.libraryVersion
    val entries = remember(version) {
        controller.library.history
            .groupBy { it.provider to it.loadUrl }
            .map { (_, list) -> list.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
            .take(20)
    }
    if (entries.isEmpty()) return
    val state = rememberLazyListState()
    RowSection(LocalStrings.current.continueWatching, null, state) {
        items(entries, key = { it.key }) { entry ->
            PosterCard(
                title = entry.showName,
                posterUrl = entry.posterUrl,
                subtitle = historySubtitle(entry),
                progress = entry.progress?.takeIf { !entry.finished },
                onPlay = { controller.quickPlay(Screen.Detail(entry.provider, entry.loadUrl, entry.showName, entry.posterUrl)) },
                playBusy = controller.quickPlaying == entry.loadUrl,
                onClick = {
                    controller.navigator.open(Screen.Detail(entry.provider, entry.loadUrl, entry.showName, entry.posterUrl))
                },
            )
        }
    }
}

/** Sağlayıcı seçici: araç çubuğunda yarı saydam hap. */
@Composable
fun ProviderPicker(providers: List<MainAPI>, selected: MainAPI, onSelect: (MainAPI) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(
            Modifier.height(40.dp).clip(shape)
                .background(if (hovered) SeyirColors.surfaceHigh else SeyirColors.surface.copy(alpha = 0.86f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .focusRing(interaction, shape)
                .hoverable(interaction)
                .clickable(interaction, indication = null, role = Role.DropdownList) { open = true }
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(LocalStrings.current.pluginLabel, style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
            Spacer(Modifier.width(8.dp))
            Text(selected.name, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = SeyirColors.textMuted)
        }
        ProviderMenu(
            expanded = open,
            onDismiss = { open = false },
            providers = providers,
            isSelected = { it == selected },
            onPick = onSelect,
            closeOnPick = true,
        )
    }
}

/** Puanı arayüz dilinin ondalık ayracıyla yazar (7,9 / 7.9). */
fun formatScore(value: Double): String = String.format(I18n.current.locale, "%.1f", value)

/** Küçük afiş altındaki satır: bölüm ve kalınan yer ya da "bitti". */
fun historySubtitle(entry: seyir.app.data.WatchEntry): String {
    val t = I18n.current
    val what = if (entry.season != null || entry.episode != null) {
        listOfNotNull(entry.season?.let { t.seasonShort(it) }, entry.episode?.let { t.episodeShort(it) }).joinToString(" ")
    } else entry.provider
    return if (entry.finished) t.historyFinished(what) else "$what · ${formatTime(entry.position)}"
}

/**
 * İlk açılış: uygulamada site eklentisi ve hazır depo adresi yoktur. Kullanıcı bir eklenti deposunun
 * adresini yapıştırır; depo okunur ve kurulacak eklentiler seçim penceresinden seçilir. Dosyadan kurma ve depo
 * yönetimi Eklentiler ekranındadır.
 */
@Composable
private fun Welcome(controller: AppController) {
    val t = LocalStrings.current
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val value = url.trim()
        if (!value.startsWith("http") || busy) return
        busy = true
        message = null
        controller.pluginActions.readRepository(value) { result ->
            busy = false
            result.onSuccess { controller.pluginActions.pickerRepository = it }.onFailure { message = it.message }
        }
    }

    controller.pluginActions.pickerRepository?.let { repo ->
        PluginPickerDialog(controller, repo, onDismiss = { controller.pluginActions.pickerRepository = null }) { result ->
            controller.pluginActions.pickerRepository = null
            message = result
        }
    }

    StatePanel(
        Icons.Rounded.Extension,
        t.noPluginsYet,
        t.welcomeHint + (message?.let { " $it." } ?: ""),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeyirTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = t.welcomeRepositoryPlaceholder,
                        modifier = Modifier.width(460.dp),
                        onSubmit = ::submit,
                    )
                    Spacer(Modifier.width(10.dp))
                    SeyirButton(
                        t.addAndInstall,
                        ::submit,
                        kind = ButtonKind.Primary,
                        icon = Icons.Rounded.Download,
                        enabled = url.trim().startsWith("http"),
                        busy = busy,
                    )
                }
                TextLink(t.openPluginsScreen) {
                    controller.pluginsTab = 1
                    controller.navigator.root(seyir.app.Screen.Plugins)
                }
            }
        },
    )
}
