package seyir.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay
import seyir.app.AppController
import seyir.app.i18n.LocalStrings
import seyir.app.i18n.UiStrings
import seyir.app.player.EmbeddedPlayer
import seyir.app.player.PlayerMenu
import seyir.app.player.ThumbnailSource
import seyir.app.ui.episodeCode
import seyir.app.ui.subtitleSizes
import seyir.core.Playable
import seyir.app.ui.SeyirColors
import seyir.app.ui.formatTime
import seyir.core.player.MediaTrack
import seyir.core.player.PlayerControls
import seyir.core.player.PlayerState
import java.awt.Component
import java.awt.Dialog
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.WindowStateListener
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Uygulama içi oynatıcı: video Seyir penceresini kaplar. mpv, pencereye gömülü yerel bir tuvale çizer; yerel pencere
 * Compose içeriğinin üstünde kaldığı için denetimler tuvalin üstüne oturan saydam, sahipli ayrı bir pencerede
 * çizilir. Fare olayları o pencereye gelir; klavye ana pencerede kalır ([handlePlayerKey]).
 */
@Composable
fun PlayerScreen(controller: AppController, window: ComposeWindow, windowState: WindowState) {
    val player = controller.playback.embedded
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SwingPanel(background = Color.Black, factory = { player.surface.canvas }, modifier = Modifier.fillMaxSize())
    }
    // Oynatıcı kapanırken tam ekrandan çıkılır
    DisposableEffect(Unit) {
        player.toggleFullscreen = { toggleFullscreen(player, windowState) }
        onDispose {
            player.toggleFullscreen = null
            player.menu = null
            if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = player.placementBeforeFullscreen
        }
    }
    ControlsWindow(player.surface.canvas, window) {
        PlayerControlsLayer(
            controller = controller,
            player = player,
            fullscreen = windowState.placement == WindowPlacement.Fullscreen,
            onToggleFullscreen = { toggleFullscreen(player, windowState) },
            owner = window,
        )
    }
}

private fun toggleFullscreen(player: EmbeddedPlayer, windowState: WindowState) {
    if (windowState.placement == WindowPlacement.Fullscreen) {
        windowState.placement = player.placementBeforeFullscreen
    } else {
        player.placementBeforeFullscreen = windowState.placement
        windowState.placement = WindowPlacement.Fullscreen
    }
}

/**
 * Oynatıcı açıkken ana pencerenin tuşları: oynat/duraklat, sarma, ses, tam ekran, bölüm geçişi. Arkadaki ekranlara
 * tuş gitmesin diye (Alt bileşimleri dışında) bütün tuşlar tüketilir.
 */
fun handlePlayerKey(event: KeyEvent, controller: AppController, windowState: WindowState): Boolean {
    // Sistem ve pencere bileşimleri (Alt+F4, Ctrl+...) oynatıcıya değil pencereye kalır
    if (event.isAltPressed || event.isCtrlPressed) return false
    if (event.type != KeyEventType.KeyDown) return true
    val player = controller.playback.embedded
    val controls = player.controls
    val state = controls?.state?.value
    player.activity++
    when (event.key) {
        Key.Spacebar, Key.K, Key.MediaPlayPause -> controls?.togglePause()
        Key.DirectionLeft -> controls?.seekBy(-SMALL_SEEK_S)
        Key.DirectionRight -> controls?.seekBy(SMALL_SEEK_S)
        Key.J -> controls?.seekBy(-LARGE_SEEK_S)
        Key.L -> controls?.seekBy(LARGE_SEEK_S)
        Key.DirectionUp -> state?.let { controls.setVolume(it.volume + VOLUME_STEP) }
        Key.DirectionDown -> state?.let { controls.setVolume(it.volume - VOLUME_STEP) }
        Key.M -> state?.let { controls.setMuted(!it.muted) }
        Key.F -> toggleFullscreen(player, windowState)
        Key.N -> if (event.isShiftPressed) controller.playback.playerPrevious() else controller.playback.playerNext()
        Key.MediaNext -> controller.playback.playerNext()
        Key.MediaPrevious -> controller.playback.playerPrevious()
        // Esc sırayla: açık menüyü kapatır, tam ekrandan çıkar, oynatıcıyı kapatır
        Key.Escape -> when {
            player.menu != null -> player.menu = null
            windowState.placement == WindowPlacement.Fullscreen -> windowState.placement = player.placementBeforeFullscreen
            else -> controller.playback.stop()
        }
        else -> Unit
    }
    return true
}

/**
 * Tuvalin üstüne oturan saydam, kenarsız, odak almayan pencere. Ana pencerenin sahipliğinde olduğu için onunla
 * birlikte küçülür ve hep onun üstünde durur; boyutu ve konumu tuvali izler.
 */
@Composable
private fun ControlsWindow(anchor: Component, owner: ComposeWindow, content: @Composable () -> Unit) {
    DialogWindow(
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                isTransparent = true
                // Klavye ana pencerede kalsın: başlık çubuğu etkin görünür, kısayollar tek yerden işlenir
                focusableWindowState = false
                if (anchor.isShowing) bounds = java.awt.Rectangle(anchor.locationOnScreen, anchor.size) else setSize(1, 1)
            }
        },
        dispose = { it.dispose() },
    ) {
        val dialog = window
        DisposableEffect(anchor, owner) {
            fun sync() {
                if (anchor.isShowing && anchor.width > 0 && anchor.height > 0) {
                    val target = java.awt.Rectangle(anchor.locationOnScreen, anchor.size)
                    if (dialog.bounds != target) dialog.bounds = target
                    if (!dialog.isVisible) dialog.isVisible = true
                } else if (dialog.isVisible) {
                    dialog.isVisible = false
                }
            }
            val components = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = sync()
                override fun componentMoved(e: ComponentEvent) = sync()
                override fun componentShown(e: ComponentEvent) = sync()
                override fun componentHidden(e: ComponentEvent) = sync()
            }
            val ancestors = object : HierarchyBoundsAdapter() {
                override fun ancestorMoved(e: HierarchyEvent) = sync()
                override fun ancestorResized(e: HierarchyEvent) = sync()
            }
            val windowStates = WindowStateListener { sync() }
            anchor.addComponentListener(components)
            anchor.addHierarchyBoundsListener(ancestors)
            owner.addComponentListener(components)
            owner.addWindowStateListener(windowStates)
            sync()
            onDispose {
                anchor.removeComponentListener(components)
                anchor.removeHierarchyBoundsListener(ancestors)
                owner.removeComponentListener(components)
                owner.removeWindowStateListener(windowStates)
            }
        }
        // Tam ekran geçişi gibi olay kaçırılan durumlar için seyrek denetim
        LaunchedEffect(anchor) {
            while (true) {
                delay(400)
                if (anchor.isShowing) {
                    val target = java.awt.Rectangle(anchor.locationOnScreen, anchor.size)
                    if (dialog.bounds != target) dialog.bounds = target
                }
            }
        }
        content()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerControlsLayer(
    controller: AppController,
    player: EmbeddedPlayer,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    owner: ComposeWindow,
) {
    val t = LocalStrings.current
    val controls = player.controls
    val state by (controls?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PlayerState()) }).collectAsState()

    var pointerActivity by remember { mutableLongStateOf(0L) }
    var idle by remember { mutableStateOf(false) }
    var hoveringBars by remember { mutableStateOf(false) }
    var menu by player::menu
    var flash by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(pointerActivity, player.activity) {
        idle = false
        delay(HIDE_AFTER_MS)
        idle = true
    }
    LaunchedEffect(menu) { if (menu == null) hoveringBars = false }
    val busy = player.preparing || controls == null || !state.loaded
    val visible = !idle || state.paused || menu != null || hoveringBars || busy

    // Denetimler açıkken altyazı alt şeridin üstüne kayar (mpv'nin ölçeği pencere yüksekliğinin 720'de biridir)
    var layerHeight by remember { mutableIntStateOf(0) }
    val barPx = with(LocalDensity.current) { SUBTITLE_CLEARANCE.toPx() }
    LaunchedEffect(controls, visible, layerHeight) {
        if (controls == null || layerHeight <= 0) return@LaunchedEffect
        controls.setSubtitleMargin(if (visible) (barPx / layerHeight * 720).roundToInt() else DEFAULT_SUBTITLE_MARGIN)
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { layerHeight = it.height }
            // Tamamen saydam nokta fareyi alttaki pencereye geçirir; neredeyse saydam zemin olayları bu katmanda tutar
            .background(Color.Black.copy(alpha = 0.004f))
            .pointerHoverIcon(if (visible) PointerIcon.Default else HiddenCursor)
            .onPointerEvent(PointerEventType.Move) { pointerActivity++ }
            .onPointerEvent(PointerEventType.Enter) { pointerActivity++ }
            .onPointerEvent(PointerEventType.Press) { if (!owner.isActive) owner.toFront() }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f && controls != null) controls.setVolume(state.volume - dy.coerceIn(-3f, 3f) * VOLUME_STEP)
                pointerActivity++
            }
            .pointerInput(controls) {
                detectTapGestures(
                    onTap = {
                        if (menu != null) {
                            menu = null
                        } else if (controls != null && state.loaded) {
                            controls.togglePause()
                            flash = !state.paused to System.nanoTime()
                        }
                    },
                    onDoubleTap = { onToggleFullscreen() },
                )
            },
    ) {
        // Denetimler görünürken üstte ve altta siyaha doğru koyulaşan geçiş: açık renkli sahnelerde de başlık ve
        // çubuk okunur. Yalnız boya; fare olaylarını almaz
        AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(tween(200)), exit = fadeOut(tween(320))) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TOP_SCRIM_HEIGHT).background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.82f),
                            0.45f to Color.Black.copy(alpha = 0.48f),
                            1f to Color.Transparent,
                        ),
                    ),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BOTTOM_SCRIM_HEIGHT).background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                )
            }
        }

        // Üst şerit: geri ve başlık
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { -it / 3 },
            exit = fadeOut(tween(240)) + slideOutVertically(tween(240)) { -it / 3 },
        ) {
            TopBar(player, onBack = { controller.playback.stop() }, onHover = { hoveringBars = it })
        }

        // Ortada: hazırlanırken/önbellek beklenirken dönen gösterge, dokununca oynat/duraklat işareti
        if (busy || state.buffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(52.dp), color = SeyirColors.accent, strokeWidth = 4.dp, trackColor = Color.White.copy(alpha = 0.12f))
                if (player.preparing) {
                    Spacer(Modifier.height(16.dp))
                    Text(t.playerPreparing, style = MaterialTheme.typography.bodyMedium, color = SeyirColors.textSecondary)
                }
            }
        }
        flash?.let { (playing, id) -> CenterFlash(playing, id, Modifier.align(Alignment.Center)) }

        // Kısa ileti (kaynak değişti, sıradaki bölüm)
        NoticePill(player, Modifier.align(Alignment.TopCenter).padding(top = 92.dp))

        // Bölüm sonuna doğru "sıradaki bölüm" düğmesi (denetimler gizliyken de görünür)
        val remaining = state.duration?.let { d -> state.position?.let { d - it } }
        val upNext = player.nextTitle?.takeIf { state.loaded && !player.preparing && remaining != null && remaining <= UP_NEXT_BEFORE_END_S }
        AnimatedVisibility(
            upNext != null,
            Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = if (visible) 132.dp else 40.dp),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            UpNextCard(upNext.orEmpty()) { controller.playback.playerNext() }
        }

        // Alt şerit: ilerleme çubuğu ve düğmeler
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(240)) + slideOutVertically(tween(240)) { it / 3 },
        ) {
            BottomBar(
                state = state,
                controls = controls,
                player = player,
                fullscreen = fullscreen,
                menu = menu,
                onMenu = { menu = if (menu == it) null else it },
                onToggleFullscreen = onToggleFullscreen,
                onNext = { controller.playback.playerNext() },
                onPrevious = { controller.playback.playerPrevious() },
                onHover = { hoveringBars = it },
            )
        }

        menu?.let { open ->
            if (open == PlayerMenu.Episodes) {
                EpisodesDrawer(
                    controller, player,
                    Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 24.dp, bottom = 96.dp),
                    onClose = { menu = null },
                    onHover = { hoveringBars = it },
                )
            } else if (controls != null) {
                MenuPanel(
                    open, state, controls, controller, player, t,
                    Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 96.dp),
                    onPicked = { menu = null },
                    onHover = { hoveringBars = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(player: EmbeddedPlayer, onBack: () -> Unit, onHover: (Boolean) -> Unit) {
    val t = LocalStrings.current
    Row(
        Modifier.fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { onHover(true) }
            .onPointerEvent(PointerEventType.Exit) { onHover(false) }
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 20.dp, end = 32.dp, top = 18.dp, bottom = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t.back, onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                player.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            player.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SeyirColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BottomBar(
    state: PlayerState,
    controls: PlayerControls?,
    player: EmbeddedPlayer,
    fullscreen: Boolean,
    menu: PlayerMenu?,
    onMenu: (PlayerMenu) -> Unit,
    onToggleFullscreen: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onHover: (Boolean) -> Unit,
) {
    val t = LocalStrings.current
    val enabled = controls != null && state.loaded
    Column(
        Modifier.fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) {
                onHover(true)
                player.thumbnails?.warmUp(state)
            }
            .onPointerEvent(PointerEventType.Exit) { onHover(false) }
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 14.dp),
    ) {
        SeekBar(
            position = state.position,
            duration = state.duration,
            cachedUntil = state.cachedUntil,
            enabled = enabled,
            onSeek = { controls?.seekTo(it) },
            thumbnails = player.thumbnails,
            state = state,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerIconButton(
                if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (state.paused) t.play else t.pause,
                size = 48.dp, iconSize = 32.dp, enabled = enabled,
            ) { controls?.togglePause() }
            PlayerIconButton(Icons.Rounded.Replay10, t.seekBack10, enabled = enabled) { controls?.seekBy(-LARGE_SEEK_S) }
            PlayerIconButton(Icons.Rounded.Forward10, t.playerForward10, enabled = enabled) { controls?.seekBy(LARGE_SEEK_S) }
            VolumeControl(state, controls)
            Spacer(Modifier.width(12.dp))
            val position = state.position
            if (position != null) {
                Text(
                    formatTime(position) + (state.duration?.let { " / " + formatTime(it) } ?: ""),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, letterSpacing = 0.2.sp),
                    color = Color.White.copy(alpha = 0.92f),
                )
            }
            Spacer(Modifier.weight(1f))
            player.previousTitle?.let { title ->
                PlayerIconButton(Icons.Rounded.SkipPrevious, "${t.playerPreviousEpisode}: $title", onClick = onPrevious)
            }
            player.nextTitle?.let { title ->
                PlayerIconButton(Icons.Rounded.SkipNext, "${t.playerNextEpisode}: $title", onClick = onNext)
            }
            if (player.episodes.size > 1) {
                PlayerIconButton(Icons.Rounded.VideoLibrary, t.playerEpisodes, active = menu == PlayerMenu.Episodes) {
                    onMenu(PlayerMenu.Episodes)
                }
            }
            if (player.sources.size > 1) {
                PlayerIconButton(Icons.Rounded.Dns, t.playerSources, active = menu == PlayerMenu.Sources) { onMenu(PlayerMenu.Sources) }
            }
            if (state.subtitleTracks.isNotEmpty()) {
                PlayerIconButton(
                    Icons.Rounded.Subtitles, t.playerSubtitles,
                    active = menu == PlayerMenu.Subtitles,
                    dimmed = state.subtitleTracks.none { it.selected },
                ) { onMenu(PlayerMenu.Subtitles) }
            }
            if (state.audioTracks.size > 1) {
                PlayerIconButton(Icons.Rounded.Audiotrack, t.playerAudio, active = menu == PlayerMenu.Audio) { onMenu(PlayerMenu.Audio) }
            }
            Box {
                PlayerIconButton(Icons.Rounded.Settings, t.playerSettings, active = menu == PlayerMenu.Settings, enabled = controls != null) {
                    onMenu(PlayerMenu.Settings)
                }
                // Birden çok kalite varsa şu anki kalite dişlinin köşesinde
                state.currentQuality?.takeIf { state.qualities.size > 1 }?.let { quality ->
                    Text(
                        if (quality.height >= 2160) "4K" else if (quality.height >= 720) "HD" else "SD",
                        Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 6.dp)
                            .clip(RoundedCornerShape(3.dp)).background(SeyirColors.accent).padding(horizontal = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
                        color = SeyirColors.onAccent,
                    )
                }
            }
            PlayerIconButton(
                if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (fullscreen) t.playerExitFullscreen else t.playerFullscreen,
                onClick = onToggleFullscreen,
            )
        }
    }
}

/** Sesi kapat düğmesi; üzerine gelince yanında ses çubuğu açılır. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeControl(state: PlayerState, controls: PlayerControls?) {
    val t = LocalStrings.current
    var hovered by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (hovered) 96.dp else 0.dp, tween(180))
    Row(
        Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val silent = state.muted || state.volume <= 0.5
        PlayerIconButton(
            if (silent) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            if (state.muted) t.playerUnmute else t.playerMute,
            enabled = controls != null,
        ) { controls?.setMuted(!state.muted) }
        Box(Modifier.width(width).height(32.dp), contentAlignment = Alignment.CenterStart) {
            if (width > 8.dp) {
                ThinSlider(
                    fraction = if (state.muted) 0f else (state.volume / 100.0).toFloat().coerceIn(0f, 1f),
                    onChange = { controls?.setVolume(it * 100.0) },
                    modifier = Modifier.width(88.dp),
                )
            }
        }
    }
}

/**
 * İlerleme çubuğu: önbellekteki kısım açık, izlenen kısım vurgu renginde. Üzerine gelince kalınlaşır ve imlecin
 * altındaki zamanı gösterir; sürükleyince bırakılan yere sarar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeekBar(
    position: Double?,
    duration: Double?,
    cachedUntil: Double?,
    enabled: Boolean,
    onSeek: (Double) -> Unit,
    thumbnails: ThumbnailSource?,
    state: PlayerState,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var preview by remember(thumbnails) { mutableStateOf<ImageBitmap?>(null) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val total = duration?.takeIf { it > 0 }
    val canSeek = enabled && total != null
    val progress = dragFraction ?: if (total != null && position != null) (position / total).toFloat().coerceIn(0f, 1f) else 0f
    val buffered = if (total != null && cachedUntil != null) (cachedUntil / total).toFloat().coerceIn(0f, 1f) else 0f
    val active = canSeek && (hoverX != null || dragFraction != null)
    val thickness by animateDpAsState(if (active) 6.dp else 4.dp, tween(120))
    val thumb by animateFloatAsState(if (active) 1f else 0f, tween(120))
    val density = LocalDensity.current
    fun fractionAt(x: Float) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)

    Box(
        modifier.fillMaxWidth().height(22.dp)
            .onSizeChanged { width = it.width }
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.firstOrNull()?.position?.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .pointerInput(canSeek, total) {
                if (!canSeek || total == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragFraction = fractionAt(down.position.x)
                    down.consume()
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            dragFraction = fractionAt(change.position.x)
                            hoverX = change.position.x
                            change.consume()
                        }
                        dragFraction?.let { onSeek(it * total) }
                    } finally {
                        // Hareket yarıda kesilirse (süre değişti, dosya kapandı) çubuk sürükleme konumunda donmasın
                        dragFraction = null
                    }
                }
            }
            .semantics { contentDescription = "seek" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(thickness)) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = radius)
            if (buffered > 0f) drawRoundRect(Color.White.copy(alpha = 0.38f), size = Size(size.width * buffered, size.height), cornerRadius = radius)
            if (progress > 0f) drawRoundRect(SeyirColors.accent, size = Size(size.width * progress, size.height), cornerRadius = radius)
        }
        if (thumb > 0f) {
            val thumbPx = with(density) { 14.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((progress * width - thumbPx / 2).roundToInt(), 0) }
                    .size(14.dp * thumb).clip(CircleShape).background(SeyirColors.accent),
            )
        }
        val hover = hoverX
        val hoverSeconds = if (active && hover != null && total != null) fractionAt(hover) * total else null
        // Önizleme karesi: önbellekte yoksa imleç kısa süre durunca istenir; gelene kadar bir önceki kare kalır
        LaunchedEffect(thumbnails, hoverSeconds?.let { (it / 2).toLong() }) {
            val seconds = hoverSeconds ?: return@LaunchedEffect
            val source = thumbnails ?: return@LaunchedEffect
            source.cached(seconds)?.let { preview = it; return@LaunchedEffect }
            delay(PREVIEW_DEBOUNCE_MS)
            source.request(seconds, state) { preview = it }
        }
        if (hoverSeconds != null && hover != null) {
            val image = preview?.takeIf { thumbnails != null }
            val boxWidth = if (image != null) PREVIEW_WIDTH else 64.dp
            val boxPx = with(density) { boxWidth.toPx() }
            val imageHeight = image?.let { PREVIEW_WIDTH * (it.height.toFloat() / it.width) } ?: 0.dp
            val x = (hover - boxPx / 2).coerceIn(0f, (width - boxPx).coerceAtLeast(0f))
            val lift = with(density) { (imageHeight + 34.dp).roundToPx() }
            Column(
                Modifier.offset { IntOffset(x.roundToInt(), -lift) }
                    .width(boxWidth).shadow(12.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xF0101014)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (image != null) {
                    Image(image, null, Modifier.width(PREVIEW_WIDTH).height(imageHeight), contentScale = ContentScale.Crop)
                }
                Text(
                    formatTime(hoverSeconds),
                    Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

/** Ses için ince kaydırıcı. */
@Composable
private fun ThinSlider(fraction: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragging >= 0f) dragging else fraction
    val density = LocalDensity.current
    Box(
        modifier.height(24.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(x: Float) = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    dragging = at(down.position.x)
                    onChange(dragging)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        dragging = at(change.position.x)
                        onChange(dragging)
                        change.consume()
                    }
                    dragging = -1f
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = radius)
            drawRoundRect(Color.White, size = Size(size.width * shown, size.height), cornerRadius = radius)
        }
        val thumbPx = with(density) { 12.dp.toPx() }
        Box(
            Modifier.offset { IntOffset((shown * width - thumbPx / 2).roundToInt(), 0) }
                .size(12.dp).clip(CircleShape).background(Color.White),
        )
    }
}

/** Oynatıcı düğmesi: yuvarlak, üzerine gelince hafif zemin; [active] açık menünün düğmesi. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        active -> SeyirColors.accent
        dimmed && !hovered -> Color.White.copy(alpha = 0.6f)
        else -> Color.White
    }
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Text(
                description,
                Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xF0101014)).padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        },
        delayMillis = 500,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.ComponentRect(
            anchor = Alignment.TopCenter, alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.DpOffset(0.dp, (-8).dp),
        ),
    ) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (hovered && enabled) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .hoverable(interaction)
                .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** Dokununca ortada kısa süre görünüp kaybolan oynat/duraklat işareti. */
@Composable
private fun CenterFlash(playing: Boolean, id: Long, modifier: Modifier) {
    var shown by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) {
        delay(80)
        shown = false
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 0 else 520))
    val scale by animateFloatAsState(if (shown) 1f else 1.35f, tween(if (shown) 0 else 520))
    if (alpha <= 0.01f) return
    Box(
        modifier.size(84.dp * scale).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null,
            tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(44.dp * scale),
        )
    }
}

@Composable
private fun NoticePill(player: EmbeddedPlayer, modifier: Modifier) {
    val notice = player.notice
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice?.id) {
        val text = notice?.text ?: return@LaunchedEffect
        shown = text
        delay(NOTICE_MS)
        shown = null
    }
    AnimatedVisibility(shown != null, modifier, enter = fadeIn(tween(180)), exit = fadeOut(tween(300))) {
        Text(
            shown.orEmpty(),
            Modifier.shadow(12.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(Color(0xEE1C1C21)).padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** Bölüm sonuna doğru sağ altta çıkan "Sıradaki bölüm" kartı. */
@Composable
private fun UpNextCard(title: String, onClick: () -> Unit) {
    val t = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(if (hovered) SeyirColors.accentPressed else SeyirColors.accent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SkipNext, null, tint = SeyirColors.onAccent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(t.playerUpNext, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = SeyirColors.onAccent)
            Text(
                title, Modifier.widthLimit(), style = MaterialTheme.typography.bodySmall,
                color = SeyirColors.onAccent.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Modifier.widthLimit() = this.then(Modifier.heightIn(max = 40.dp)).then(Modifier.width(220.dp))

/** Altyazı, ses ya da ayarlar (kalite, hız) menüsü. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MenuPanel(
    menu: PlayerMenu,
    state: PlayerState,
    controls: PlayerControls,
    controller: AppController,
    player: EmbeddedPlayer,
    t: UiStrings,
    modifier: Modifier,
    onPicked: () -> Unit,
    onHover: (Boolean) -> Unit,
) {
    Column(
        modifier.width(300.dp).heightIn(max = 420.dp)
            .shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Color(0xF51C1C21))
            .onPointerEvent(PointerEventType.Enter) { onHover(true) }
            .onPointerEvent(PointerEventType.Exit) { onHover(false) }
            .pointerInput(Unit) { detectTapGestures { } }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        when (menu) {
            PlayerMenu.Subtitles -> {
                MenuHeader(t.playerSubtitles)
                MenuItem(t.playerSubtitlesOff, selected = state.subtitleTracks.none { it.selected }) {
                    controls.selectSubtitle(null)
                    onPicked()
                }
                state.subtitleTracks.forEachIndexed { index, track ->
                    MenuItem(trackLabel(track, index, t), trackDetail(track), selected = track.selected) {
                        controls.selectSubtitle(track.id)
                        onPicked()
                    }
                }
                Spacer(Modifier.height(6.dp))
                MenuHeader(t.playerSubtitleSize)
                val current = controller.settings.subtitleScale
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subtitleSizes(t).forEachIndexed { index, (scale, label) ->
                        SizeChip(label, index, selected = kotlin.math.abs(scale - current) < 0.01, Modifier.weight(1f)) {
                            controls.setSubtitleScale(scale)
                            controller.updateSettings { it.copy(subtitleScale = scale) }
                        }
                    }
                }
            }
            PlayerMenu.Sources -> {
                MenuHeader(t.playerSources)
                player.sources.forEach { source ->
                    MenuItem(source.name, source.detail, selected = source.index == player.currentSource) {
                        controller.playback.playerSwitchSource(source.index)
                        onPicked()
                    }
                }
            }
            PlayerMenu.Episodes -> Unit
            PlayerMenu.Audio -> {
                MenuHeader(t.playerAudio)
                state.audioTracks.forEachIndexed { index, track ->
                    MenuItem(trackLabel(track, index, t), trackDetail(track), selected = track.selected) {
                        controls.selectAudio(track.id)
                        onPicked()
                    }
                }
            }
            PlayerMenu.Settings -> {
                if (state.qualities.size > 1) {
                    MenuHeader(t.playerQuality)
                    val current = state.currentQuality
                    state.qualities.forEach { layer ->
                        MenuItem(
                            layer.label,
                            layer.kbps.takeIf { it > 0 }?.let { if (it >= 1000) String.format(Locale.ROOT, "%.1f Mbps", it / 1000) else "${it.roundToInt()} kbps" },
                            selected = layer == current,
                        ) {
                            if (layer != current) controls.selectQuality(layer.editionId)
                            onPicked()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                MenuHeader(t.playerSpeed)
                SPEEDS.forEach { speed ->
                    MenuItem(
                        if (speed == 1.0) t.playerSpeedNormal else String.format(Locale.ROOT, "%sx", speed.toString().removeSuffix(".0")),
                        selected = kotlin.math.abs(state.speed - speed) < 0.01,
                    ) {
                        controls.setSpeed(speed)
                        onPicked()
                    }
                }
            }
        }
    }
}

/** Altyazı boyutu seçeneği: [step]'e göre büyüyen bir "A" harfi; adı ipucu ve erişilebilirlik metnidir. */
@Composable
private fun SizeChip(label: String, step: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> SeyirColors.accent.copy(alpha = 0.18f)
                    hovered -> Color.White.copy(alpha = 0.08f)
                    else -> Color.White.copy(alpha = 0.04f)
                },
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "A",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = (12 + step * 3).sp, fontWeight = FontWeight.SemiBold),
            color = if (selected) SeyirColors.accent else SeyirColors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Sağdan açılan bölüm listesi: sezon başlıklarıyla, oynayan bölüm vurgulu, izlenenler tikli, yarım kalanlarda
 * ilerleme çizgisi. Bölüme tıklayınca bağlantıları aranıp açılır.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EpisodesDrawer(
    controller: AppController,
    player: EmbeddedPlayer,
    modifier: Modifier,
    onClose: () -> Unit,
    onHover: (Boolean) -> Unit,
) {
    val t = LocalStrings.current
    val episodes = player.episodes
    val currentIndex = episodes.indexOfFirst { it.data == player.currentEpisode }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    // Geçmiş değişince (bölüm bitti, izlendi işaretlendi) liste yenilensin
    @Suppress("UNUSED_VARIABLE") val version = controller.libraryVersion
    val provider = player.provider
    Column(
        modifier.fillMaxHeight().width(420.dp)
            .shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(Color(0xF5161619))
            .onPointerEvent(PointerEventType.Enter) { onHover(true) }
            .onPointerEvent(PointerEventType.Exit) { onHover(false) }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.playerEpisodes, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                Text(player.title, style = MaterialTheme.typography.bodySmall, color = SeyirColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerIconButton(Icons.Rounded.Close, t.close, onClick = onClose)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            itemsIndexed(episodes, key = { index, item -> "$index:${item.data}" }) { index, item ->
                val season = item.season
                val previousSeason = episodes.getOrNull(index - 1)?.season
                if (season != null && (index == 0 || season != previousSeason)) {
                    Text(
                        t.seasonLabel(season),
                        Modifier.padding(start = 24.dp, top = if (index == 0) 4.dp else 18.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.4.sp),
                        color = SeyirColors.textMuted,
                    )
                }
                val entry = provider?.let { controller.library.entry(it, item.data) }
                EpisodeItem(
                    item = item,
                    current = item.data == player.currentEpisode,
                    finished = entry?.finished == true,
                    progress = entry?.progress?.takeIf { entry.finished.not() && it > 0.01f },
                ) {
                    controller.playback.playerPlayEpisode(item)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun EpisodeItem(item: Playable, current: Boolean, finished: Boolean, progress: Float?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val code = episodeCode(item)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    current -> SeyirColors.accent.copy(alpha = 0.14f)
                    hovered -> Color.White.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, enabled = !current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                code,
                style = MaterialTheme.typography.labelMedium,
                color = if (current) SeyirColors.accent else SeyirColors.textMuted,
            )
            val title = item.title.takeIf { it.isNotBlank() && it != code }
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current) Color.White else SeyirColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val radius = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = radius)
                    drawRoundRect(SeyirColors.accent, size = Size(size.width * progress, size.height), cornerRadius = radius)
                }
            }
        }
        if (current) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.PlayArrow, null, tint = SeyirColors.accent, modifier = Modifier.size(20.dp))
        } else if (finished) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.CheckCircle, null, tint = SeyirColors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.4.sp),
        color = SeyirColors.textMuted,
    )
}

@Composable
private fun MenuItem(label: String, detail: String? = null, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, tint = SeyirColors.accent, modifier = Modifier.size(18.dp))
        }
        Text(
            label, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.White else SeyirColors.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted, maxLines = 1) }
    }
}

/**
 * İzin adı: başlık, yoksa dil adı (arayüz dilinde), o da yoksa "İz n". Başlık yalnızca dilin İngilizce ya da yerel
 * adıysa ("English") arayüz dilindeki ad gösterilir.
 */
private fun trackLabel(track: MediaTrack, index: Int, t: UiStrings): String {
    val locale = Locale.forLanguageTag(if (t === seyir.app.i18n.EnglishStrings) "en" else "tr")
    val tag = track.lang?.let { Locale.forLanguageTag(it) }
    val language = tag?.getDisplayLanguage(locale)
        ?.takeIf { it.isNotBlank() && !it.equals(track.lang, true) }
        ?.replaceFirstChar { it.titlecase(locale) }
    val title = track.title?.takeUnless { tag != null && (it.equals(tag.getDisplayLanguage(Locale.ENGLISH), true) || it.equals(tag.getDisplayLanguage(tag), true)) }
    return when {
        title != null && language != null && !title.contains(language, ignoreCase = true) -> "$title · $language"
        title != null -> title
        language != null -> language
        else -> t.playerTrack(index + 1)
    }
}

private fun trackDetail(track: MediaTrack): String? = listOfNotNull(
    track.codec?.uppercase(Locale.ROOT),
    track.channels?.let { if (it == 6) "5.1" else if (it == 8) "7.1" else if (it == 2) "Stereo" else null },
).joinToString(" · ").ifBlank { null }

/** Denetimler gizliyken imleç de gizlenir. */
private val HiddenCursor: PointerIcon by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "seyir-hidden"))
}

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** Üst ve alt koyu geçişlerin yüksekliği (başlık ve alt şeridi rahatça kaplar). */
private val TOP_SCRIM_HEIGHT = 200.dp
private val BOTTOM_SCRIM_HEIGHT = 280.dp

/** İleri sarma önizlemesinin genişliği ve imleç durunca kare istemeden önceki bekleme. */
private val PREVIEW_WIDTH = 192.dp
private const val PREVIEW_DEBOUNCE_MS = 90L

/** Denetimler açıkken altyazının alt kenardan uzaklığı (alt şeridin yüksekliği kadar). */
private val SUBTITLE_CLEARANCE = 118.dp

/** mpv'nin varsayılan altyazı payı. */
private const val DEFAULT_SUBTITLE_MARGIN = 22
private const val HIDE_AFTER_MS = 2_800L
private const val NOTICE_MS = 4_000L
private const val UP_NEXT_BEFORE_END_S = 40.0
private const val SMALL_SEEK_S = 5.0
private const val LARGE_SEEK_S = 10.0
private const val VOLUME_STEP = 5.0
