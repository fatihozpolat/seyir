package seyir.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import seyir.app.AppController
import seyir.app.Screen
import seyir.app.i18n.LocalStrings

/** Afiş ızgarası; Devam et ve Listem aynı ölçüleri kullanır. */
@Composable
private fun PosterGrid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = state,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 4.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
        ScrollBar(state)
    }
}

/** "Devam et": her yapımın en son izlenen öğesi, ilerlemesiyle. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryScreen(controller: AppController) {
    val t = LocalStrings.current
    val version = controller.libraryVersion
    val entries = remember(version) {
        controller.library.history
            .groupBy { it.provider to it.loadUrl }
            .map { (_, list) -> list.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t.continueWatching, if (entries.isEmpty()) null else t.countTitles(entries.size))
        if (entries.isEmpty()) {
            EmptyPanel(
                t.nothingWatchedYet,
                t.nothingWatchedHint,
                Icons.Rounded.History,
            )
            return
        }
        PosterGrid {
            items(entries, key = { it.key }) { entry ->
                var menu by remember { mutableStateOf(false) }
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                fun remove() {
                    controller.library.history
                        .filter { it.provider == entry.provider && it.loadUrl == entry.loadUrl }
                        .forEach { controller.library.removeHistory(it.key) }
                    controller.libraryChanged()
                }
                // Kaldırma düğmesi klavyeyle de erişilebilsin: kart ya da düğmesi odaktayken görünür,
                // odaktaki kartta Delete tuşu doğrudan kaldırır
                var focusInside by remember { mutableStateOf(false) }
                Box(Modifier.hoverable(hover)
                    .onFocusChanged { focusInside = it.hasFocus }
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Delete) {
                            remove()
                            true
                        } else false
                    }
                    .onPointerEvent(PointerEventType.Press) {
                    if (it.button == PointerButton.Secondary) menu = true
                }) {
                    PosterCard(
                        title = entry.showName,
                        posterUrl = entry.posterUrl,
                        subtitle = historySubtitle(entry),
                        progress = entry.progress?.takeIf { !entry.finished },
                        width = androidx.compose.ui.unit.Dp.Unspecified,
                        onPlay = { controller.quickPlay(Screen.Detail(entry.provider, entry.loadUrl, entry.showName, entry.posterUrl)) },
                        playBusy = controller.quickPlaying == entry.loadUrl,
                        onClick = {
                            controller.navigator.open(
                                Screen.Detail(entry.provider, entry.loadUrl, entry.showName, entry.posterUrl)
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = SeyirColors.surfaceHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text(t.removeFromHistory) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = SeyirColors.textMuted) },
                            onClick = {
                                menu = false
                                remove()
                            },
                        )
                    }
                    // Sağ tık bilinmeyebilir; üzerine gelince afişin köşesinde kaldırma düğmesi çıkar
                    if (hovered || focusInside) {
                        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                            QuietIconButton(Icons.Rounded.Close, t.removeFromHistory, size = 32.dp, filled = true, tint = SeyirColors.text) {
                                remove()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksScreen(controller: AppController) {
    val t = LocalStrings.current
    val version = controller.libraryVersion
    val bookmarks = remember(version) { controller.library.bookmarks }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t.navLibrary, if (bookmarks.isEmpty()) null else t.countTitles(bookmarks.size))
        if (bookmarks.isEmpty()) {
            EmptyPanel(
                t.listEmpty,
                t.listEmptyHint,
                Icons.Rounded.BookmarkBorder,
            )
            return
        }
        PosterGrid {
            items(bookmarks, key = { it.provider + it.url }) { b ->
                PosterCard(
                    title = b.name,
                    posterUrl = b.posterUrl,
                    subtitle = listOfNotNull(typeLabel(b.type), b.provider).joinToString(" · "),
                    width = androidx.compose.ui.unit.Dp.Unspecified,
                    onPlay = { controller.quickPlay(Screen.Detail(b.provider, b.url, b.name, b.posterUrl)) },
                    playBusy = controller.quickPlaying == b.url,
                    onClick = { controller.navigator.open(Screen.Detail(b.provider, b.url, b.name, b.posterUrl)) },
                )
            }
        }
    }
}
