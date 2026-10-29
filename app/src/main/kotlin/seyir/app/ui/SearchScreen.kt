package seyir.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.Role
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import seyir.app.GroupEntry
import seyir.app.SearchProviderResult
import seyir.app.TitleGroup
import seyir.app.groupByTitle
import seyir.app.yearFromTitle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import seyir.app.AppController
import seyir.app.Screen
import seyir.app.i18n.LocalStrings

@Composable
fun SearchScreen(controller: AppController) {
    val t = LocalStrings.current
    val state = controller.search.state
    var text by remember { mutableStateOf(state.query) }
    var showFailures by remember { mutableStateOf(false) }
    val grouped = controller.settings.groupSearchByTitle
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller.searchFocusTick) { focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        val filter = state.providerFilter
        val searched = controller.enabledProviders.count { filter == null || it.name in filter }
        ScreenHeader(t.navSearch, if (filter == null) t.searchScopeAll(searched) else t.searchScopeSome(searched))
        Row(
            Modifier.padding(horizontal = PageGutter).widthIn(max = 1120.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeyirTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = t.searchPlaceholder,
                leadingIcon = Icons.Rounded.Search,
                large = true,
                focusRequester = focus,
                onSubmit = { controller.search.run(text) },
            )
            Spacer(Modifier.width(12.dp))
            ProviderFilter(controller) { if (state.query.isNotEmpty()) controller.search.run(state.query) }
        }

        if (state.query.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SeyirColors.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        t.searchWaiting(state.pending, state.results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = SeyirColors.textMuted,
                    )
                } else {
                    Text(
                        t.searchSummary(state.query, state.results.sumOf { it.items.size }, state.results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = SeyirColors.textMuted,
                    )
                }
                if (state.failed.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TextLink(if (showFailures) t.hideErrors else t.providersFailed(state.failed.size)) {
                        showFailures = !showFailures
                    }
                }
                Spacer(Modifier.weight(1f))
                GroupToggle(grouped) { on -> controller.updateSettings { it.copy(groupSearchByTitle = on) } }
            }
            if (showFailures) {
                Column(Modifier.padding(horizontal = PageGutter, vertical = 6.dp).widthIn(max = 880.dp)) {
                    state.failed.forEach { (name, message) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(name, Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium, color = SeyirColors.textSecondary)
                            Text(
                                message.take(160),
                                style = MaterialTheme.typography.labelMedium,
                                color = SeyirColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        when {
            state.query.isEmpty() -> EmptyPanel(
                t.searchAllAtOnce,
                t.searchHint,
                Icons.Rounded.Search,
            )
            state.results.isEmpty() && state.running -> CenteredLoading(t.searching)
            state.results.isEmpty() -> EmptyPanel(
                t.noResults,
                t.noResultsHint,
                Icons.Rounded.SearchOff,
            )
            else -> Box(Modifier.fillMaxSize()) {
                // Sıralama her yeniden çizimde değil, yalnız sonuçlar/sorgu değişince hesaplanır
                val ranked = remember(state.query, state.revision) { rankResults(state.query, state.results.toList()) }
                if (grouped) {
                    val groups = remember(state.query, state.revision) {
                        groupByTitle(ranked.map { it.provider to it.items }, { it.name }, { it.releaseYear() })
                            .sortedWith(compareBy({ matchRank(state.query, it.primary.title) }, { -it.providers.size }))
                    }
                    GroupedResults(
                        groups,
                        onPlay = { entry -> controller.quickPlay(Screen.Detail(entry.provider, entry.item.url, entry.item.name, entry.item.posterUrl)) },
                        busyUrl = controller.quickPlaying,
                    ) { entry ->
                        controller.navigator.open(Screen.Detail(entry.provider, entry.item.url, entry.item.name, entry.item.posterUrl))
                    }
                } else {
                    val listState = rememberLazyListState()
                    // Yeni gelen sonuç başa sıralanınca liste kaymasın: kullanıcı en üstteyse üstte kalır
                    LaunchedEffect(ranked.firstOrNull()?.key) {
                        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        // Anahtar sağlayıcı sırasıdır: aynı adı taşıyan iki sağlayıcı Compose'u çökertmesin
                        items(ranked, key = { it.key }) { (_, provider, results) ->
                            PosterRow(
                                provider,
                                results,
                                caption = t.resultsCount(results.size),
                                onPlay = { item -> controller.quickPlay(Screen.Detail(provider, item.url, item.name, item.posterUrl)) },
                                busyUrl = controller.quickPlaying,
                            ) { item ->
                                controller.navigator.open(Screen.Detail(provider, item.url, item.name, item.posterUrl))
                            }
                        }
                        item { Spacer(Modifier.height(48.dp)) }
                    }
                    ScrollBar(listState)
                }
            }
        }
    }
}

/** Sonuçtaki yapımın yılı: türün kendi alanından, yoksa başlıktaki "(2019)" ekinden. */
private fun SearchResponse.releaseYear(): Int? =
    (this as? MovieSearchResponse)?.year ?: (this as? TvSeriesSearchResponse)?.year ?: yearFromTitle(name)

/** "Başlığa göre grupla" anahtarı. */
@Composable
private fun GroupToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalStrings.current
    val shape = RoundedCornerShape(6.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.clip(shape)
            .focusRing(interaction, shape)
            .toggleable(checked, interaction, indication = null, role = Role.Switch, onValueChange = onChange)
    ) {
        Chip(t.groupByTitle, highlighted = checked)
    }
}

/** Aynı başlığı bulan sağlayıcıların tek kartta toplandığı ızgara. */
@Composable
private fun GroupedResults(
    groups: List<TitleGroup<SearchResponse>>,
    onPlay: (GroupEntry<SearchResponse>) -> Unit,
    busyUrl: String?,
    onOpen: (GroupEntry<SearchResponse>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Aynı anahtar çıkarsa (ör. adı boş yapımlar) sıra numarası eklenir; Compose çökmesin
    val keys = remember(groups) {
        val seen = HashMap<String, Int>()
        groups.map { g ->
            val base = "${g.key}|${g.year}|${g.primary.provider}|${g.primary.item.url}"
            val n = seen.merge(base, 1, Int::plus)!!
            if (n == 1) base else "$base#$n"
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.FixedSize(160.dp),
            Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 14.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(groups, key = { index, _ -> keys[index] }) { _, group -> GroupedResultCard(group, onPlay, busyUrl, onOpen) }
        }
        ScrollBar(gridState)
    }
}

/**
 * Birleşik sonuç kartı: tıklanınca en iyi sağlayıcıdaki yapım açılır; altındaki satırdan
 * (birden çok sağlayıcı varsa) başka bir sağlayıcı seçilir.
 */
@Composable
private fun GroupedResultCard(
    group: TitleGroup<SearchResponse>,
    onPlay: (GroupEntry<SearchResponse>) -> Unit,
    busyUrl: String?,
    onOpen: (GroupEntry<SearchResponse>) -> Unit,
) {
    val t = LocalStrings.current
    val primary = group.primary
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(160.dp)) {
        PosterCard(
            title = primary.item.name,
            posterUrl = primary.item.posterUrl,
            subtitle = listOfNotNull(typeLabel(primary.item.type?.name), group.year?.toString()).joinToString(" · ").ifEmpty { null },
            posterHeaders = primary.item.posterHeaders,
            onPlay = { onPlay(primary) },
            playBusy = busyUrl == primary.item.url,
            onClick = { onOpen(primary) },
        )
        Spacer(Modifier.height(4.dp))
        val providerNames = group.providers.joinToString(" · ")
        if (group.entries.size < 2) {
            Text(providerNames, style = MaterialTheme.typography.labelSmall, color = SeyirColors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .focusRing(interaction, shape)
                        .clickable(interaction, indication = null, role = Role.DropdownList) { menuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        providerNames,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = SeyirColors.accent,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Rounded.ExpandMore, t.chooseProvider, Modifier.size(16.dp), tint = SeyirColors.textMuted)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = SeyirColors.surfaceHigh,
                    border = BorderStroke(1.dp, SeyirColors.hairline),
                ) {
                    group.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.provider, style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        entry.item.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SeyirColors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 240.dp),
                                    )
                                }
                            },
                            onClick = { menuOpen = false; onOpen(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** Sorgu ile ada göre eşleşme derecesi: tam (0), başlangıç (1), içerir (2), diğer (3). */
private fun matchRank(query: String, name: String): Int {
    val q = query.trim().lowercase()
    val n = displayTitle(name).trim().lowercase()
    return when {
        n == q -> 0
        n.startsWith(q) -> 1
        n.contains(q) -> 2
        else -> 3
    }
}

/**
 * Sonuçlar geliş sırasıyla değil, en iyi eşleşmesi en iyi olan sağlayıcı önde olacak şekilde dizilir;
 * her sağlayıcının kendi sonuçları da aynı ölçüye göre sıralanır.
 */
private fun rankResults(query: String, results: List<SearchProviderResult>): List<SearchProviderResult> =
    results.map { result ->
        result.copy(items = result.items.distinctBy { it.url }.sortedBy { matchRank(query, it.name) })
    }.sortedWith(compareBy({ it.items.minOfOrNull { item -> matchRank(query, item.name) } ?: 3 }, { -it.items.size }, { it.key }))

/**
 * Aramanın hangi sağlayıcılarda yapılacağı (varsayılan: hepsi). "Hepsi" seçiliyken bir sağlayıcıya
 * tıklamak yalnız onu seçer; sonraki tıklamalar ekler/çıkarır. Hepsi seçilince ya da hiçbiri kalmayınca
 * süzgeç kalkar.
 */
@Composable
private fun ProviderFilter(controller: AppController, onChanged: () -> Unit) {
    val t = LocalStrings.current
    val state = controller.search.state
    val providers = controller.enabledProviders
    val filter = state.providerFilter
    var open by remember { mutableStateOf(false) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)

    fun update(next: Set<String>?) {
        state.providerFilter = next?.takeIf { it.isNotEmpty() && it.size < providers.size }
        onChanged()
    }

    Box {
        Row(
            Modifier.height(56.dp).clip(shape)
                .background(SeyirColors.surface)
                .border(1.dp, SeyirColors.hairline, shape)
                .focusRing(interaction, shape)
                .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp), tint = SeyirColors.textMuted)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    filter == null -> t.allProviders
                    filter.size == 1 -> filter.first()
                    else -> t.providersCount(filter.size)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (filter == null) SeyirColors.textSecondary else SeyirColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = SeyirColors.textMuted)
        }
        ProviderMenu(
            expanded = open,
            onDismiss = { open = false },
            providers = providers,
            isSelected = { filter != null && it.name in filter },
            onPick = { api ->
                update(
                    when {
                        filter == null -> setOf(api.name)
                        api.name in filter -> filter - api.name
                        else -> filter + api.name
                    }
                )
            },
            closeOnPick = false,
            allLabel = t.all,
            allSelected = filter == null,
            onPickAll = { update(null) },
        )
    }
}
