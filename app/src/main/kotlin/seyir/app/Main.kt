package seyir.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import seyir.app.ui.BookmarksScreen
import seyir.app.ui.ButtonKind
import seyir.app.ui.CenteredLoading
import seyir.app.ui.DetailScreen
import seyir.app.ui.DownloadsScreen
import seyir.app.ui.formatTime
import seyir.app.ui.HistoryScreen
import seyir.app.ui.HomeScreen
import seyir.app.ui.LinksDialog
import seyir.app.ui.PageGutter
import seyir.app.ui.PluginsScreen
import seyir.app.ui.QuietIconButton
import seyir.app.ui.SearchScreen
import seyir.app.ui.SettingsScreen
import seyir.app.ui.SeyirButton
import seyir.app.ui.SeyirColors
import seyir.app.ui.SeyirTheme
import seyir.app.ui.focusRing
import seyir.app.ui.player.PlayerScreen
import seyir.app.ui.player.handlePlayerKey
import androidx.compose.ui.awt.ComposeWindow
import seyir.core.AppDirs
import seyir.core.RotatingLogStream
import seyir.core.SeyirLog
import seyir.app.i18n.I18n
import seyir.app.i18n.LocalStrings
import seyir.app.i18n.UiStrings
import seyir.core.net.Network
import java.io.File
import java.io.PrintStream

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    redirectLogs()
    application {
        val state = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val scope = rememberCoroutineScope()
        val controller = remember { AppController(scope).also { it.start() } }
        DisposableEffect(Unit) { onDispose { controller.shutdown() } }
        // Pencere içinde yakalanmamış hata olursa Compose varsayılan olarak pencereyi kapatıyor
        // (tek pencere olduğu için uygulama da kapanıyor). Bunun yerine hata günlüğe yazılır ve
        // pencere sıfırdan kurulur; uygulama durumu AppController'da olduğu için kaybolmaz.
        var windowGeneration by remember { mutableIntStateOf(0) }
        val exceptionHandler = remember {
            WindowExceptionHandlerFactory {
                WindowExceptionHandler { error ->
                    controller.recoverFromUiError(error)
                    windowGeneration++
                }
            }
        }

        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides exceptionHandler) {
            key(windowGeneration) { MainWindow(controller, state) }
        }
    }
}

@Composable
private fun ApplicationScope.MainWindow(controller: AppController, state: WindowState) {
    Window(
        onCloseRequest = {
            controller.shutdown()
            exitApplication()
        },
        state = state,
        title = "Seyir",
        icon = painterResource("seyir.png"),
        // Önizleme aşaması: metin kutusundayken de çalışan bileşimler (Alt+Sol, Ctrl+F); oynatıcı açıkken bütün
        // tuşlar oynatıcıya gider
        onPreviewKeyEvent = { event ->
            if (controller.playback.embedded.open) {
                handlePlayerKey(event, controller, state)
            } else {
                controller.handleShortcut(event, preview = true)
            }
        },
        // Gelen aşama: Esc ve "/" yalnız alt bileşenler (menü, pencere, metin kutusu) tüketmediyse
        onKeyEvent = { event -> controller.handleShortcut(event, preview = false) },
    ) {
        LaunchedEffect(Unit) { DarkTitleBar.apply(window) }
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                // Posterler de engelli alan adlarında durduğu için uygulamanın ağ istemcisi kullanılır
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { ImageCallFactory })) }
                .memoryCache { MemoryCache.Builder().maxSizeBytes(160L * 1024 * 1024).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(AppDirs.data, "cache/images").toOkioPath())
                        .maxSizeBytes(300L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .build()
        }
        // Dil ayarı değişince bütün arayüz yeni metinlerle yeniden çizilir
        val strings = I18n.resolve(controller.settings.language)
        I18n.current = strings
        CompositionLocalProvider(LocalStrings provides strings) {
            SeyirTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    AppShell(controller, window, state)
                }
            }
        }
    }
}

/** Pencere kısayolunu işler; işlediyse true döner. */
private fun AppController.handleShortcut(event: KeyEvent, preview: Boolean): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = shortcutFor(
        key = event.key,
        codePoint = event.utf16CodePoint,
        alt = event.isAltPressed,
        ctrl = event.isCtrlPressed,
        preview = preview,
        overlayOpen = linksRequest != null || pluginActions.pickerRepository != null,
        canGoBack = navigator.canGoBack,
    ) ?: return false
    when (action) {
        ShortcutAction.Back -> navigator.back()
        ShortcutAction.FocusSearch -> openSearch()
    }
    return true
}

/**
 * Görsel istemcisi: OkHttp'nin varsayılan `okhttp/x` kimliği Cloudflare'e takıldığı için tarayıcı
 * User-Agent'ı ve (verilmemişse) görselin kendi sitesini Referer olarak ekler. İstemci her çağrıda
 * o anki [Network.client]'tan alınır; vekil/DoH ayarı değişince görseller de yeni ayarı kullanır.
 */
private object ImageCallFactory : okhttp3.Call.Factory {
    private class Cached(val base: okhttp3.OkHttpClient, val image: okhttp3.OkHttpClient)

    @Volatile
    private var cached: Cached? = null

    override fun newCall(request: okhttp3.Request): okhttp3.Call = imageClient().newCall(request)

    private fun imageClient(): okhttp3.OkHttpClient {
        val base = Network.client
        cached?.takeIf { it.base === base }?.let { return it.image }
        val image = base.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                if (request.header("User-Agent").isNullOrBlank()) builder.header("User-Agent", com.lagradost.cloudstream3.USER_AGENT)
                if (request.header("Referer").isNullOrBlank()) builder.header("Referer", "${request.url.scheme}://${request.url.host}/")
                chain.proceed(builder.build())
            }
            .build()
        cached = Cached(base, image)
        return image
    }
}

/**
 * Kütüphane ve eklenti günlükleri konsola ve `seyir.log` dosyasına gider; dosya 5 MB'ı aşınca
 * `seyir.1.log`'a döner (bir eski dosya tutulur). `java.util.logging` çıktısı da aynı yere akar.
 */
private fun redirectLogs() {
    val stream = PrintStream(RotatingLogStream(File(AppDirs.data, "seyir.log")), true, Charsets.UTF_8)
    System.setOut(stream)
    System.setErr(stream)
    SeyirLog.installJulBridge()
}

private data class NavItem(val screen: Screen, val label: (UiStrings) -> String, val icon: ImageVector, val selectedIcon: ImageVector)

private val navItems = listOf(
    NavItem(Screen.Home, { it.navHome }, Icons.Outlined.Home, Icons.Rounded.Home),
    NavItem(Screen.Search, { it.navSearch }, Icons.Outlined.Search, Icons.Rounded.Search),
    NavItem(Screen.History, { it.navContinue }, Icons.Outlined.History, Icons.Rounded.History),
    NavItem(Screen.Bookmarks, { it.navLibrary }, Icons.Outlined.Bookmarks, Icons.Rounded.Bookmarks),
    NavItem(Screen.Downloads, { it.navDownloads }, Icons.Outlined.FileDownload, Icons.Rounded.Download),
    NavItem(Screen.Plugins, { it.navPlugins }, Icons.Outlined.Extension, Icons.Rounded.Extension),
)

private val settingsItem = NavItem(Screen.Settings, { it.navSettings }, Icons.Outlined.Settings, Icons.Rounded.Settings)

@Composable
private fun AppShell(controller: AppController, window: ComposeWindow, windowState: WindowState) {
    val t = LocalStrings.current
    val navigator = controller.navigator
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(SeyirColors.background)) {
            NavRail(navigator)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Geri düğmesi içerikten önce kurulur ki Tab sırasında içerikten önce gelsin; zIndex
                    // ile yine de içeriğin üstünde çizilir
                    if (navigator.canGoBack) {
                        Box(Modifier.zIndex(1f).padding(start = 20.dp, top = 18.dp)) {
                            QuietIconButton(Icons.AutoMirrored.Rounded.ArrowBack, t.back, filled = true, tint = SeyirColors.text) {
                                navigator.back()
                            }
                        }
                    }
                    if (!controller.ready) {
                        CenteredLoading(controller.status)
                    } else {
                        when (val screen = navigator.current) {
                            Screen.Home -> HomeScreen(controller)
                            Screen.Search -> SearchScreen(controller)
                            Screen.History -> HistoryScreen(controller)
                            Screen.Bookmarks -> BookmarksScreen(controller)
                            Screen.Downloads -> DownloadsScreen(controller)
                            Screen.Plugins -> PluginsScreen(controller)
                            Screen.Settings -> SettingsScreen(controller)
                            is Screen.Detail -> DetailScreen(controller, screen)
                        }
                    }
                }
                StatusBar(controller)
            }
        }
        // Uygulama içi oynatıcı bütün pencereyi kaplar
        if (controller.playback.embedded.open) PlayerScreen(controller, window, windowState)
    }
    controller.linksRequest?.let { context ->
        LinksDialog(controller, context, onDismiss = { controller.linksRequest = null })
    }
}

/** Sol ray: üstte simge, ortada ana ekranlar, en altta Ayarlar. */
@Composable
private fun NavRail(navigator: Navigator) {
    val root = navigator.stack.first()
    Column(
        Modifier.fillMaxHeight().width(88.dp).background(SeyirColors.background)
            .drawBehind {
                drawLine(SeyirColors.hairline, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
            }
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(painterResource("seyir-mark.png"), "Seyir", Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text("Seyir", style = MaterialTheme.typography.labelLarge, color = SeyirColors.text)
        Spacer(Modifier.height(22.dp))
        navItems.forEach { item -> NavRailItem(item, root == item.screen) { navigator.root(item.screen) } }
        Spacer(Modifier.weight(1f))
        NavRailItem(settingsItem, root == settingsItem.screen) { navigator.root(settingsItem.screen) }
    }
}

@Composable
private fun NavRailItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        when {
            selected -> SeyirColors.surfaceHigh
            hovered -> SeyirColors.surface
            else -> Color.Transparent
        },
        tween(160),
    )
    val content = when {
        selected -> SeyirColors.text
        hovered -> SeyirColors.textSecondary
        else -> SeyirColors.textMuted
    }
    Column(
        Modifier.size(72.dp, 60.dp)
            .clip(shape)
            .background(background)
            .focusRing(interaction, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) item.selectedIcon else item.icon, null, tint = if (selected) SeyirColors.accent else content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            item.label(LocalStrings.current),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.sp),
            color = content,
            maxLines = 1,
        )
    }
}

/** Alt şerit: çalan içerik (bilgisayarda ya da televizyonda) ve son oynatma hatası. */
@Composable
private fun StatusBar(controller: AppController) {
    val t = LocalStrings.current
    val playing = controller.playback.nowPlaying
    val casting = controller.cast.state
    val error = controller.playback.lastError ?: controller.downloads.lastError ?: controller.uiError
    val update = controller.updates.available?.takeIf { !controller.updates.dismissed }
    if (playing == null && casting == null && error == null && update == null) return
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(SeyirColors.surface)
            .drawBehind { drawLine(SeyirColors.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
            .padding(start = PageGutter, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (casting != null) {
            CastControls(controller, casting)
        } else if (playing != null) {
            Icon(Icons.Rounded.PlayCircle, null, tint = SeyirColors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(t.nowPlaying, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
            Spacer(Modifier.width(10.dp))
            Text(
                playing, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SeyirButton(t.stop, { controller.playback.stop() }, kind = ButtonKind.Quiet, icon = Icons.Rounded.Stop)
        } else if (error == null && update != null) {
            Icon(Icons.Rounded.SystemUpdateAlt, null, tint = SeyirColors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                t.appReleased(update.version),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SeyirButton(t.later, { controller.updates.dismissed = true }, kind = ButtonKind.Quiet)
            Spacer(Modifier.width(8.dp))
            SeyirButton(
                if (seyir.core.AppUpdate.installerFor(update) != null) t.update else t.releasePage,
                { controller.updates.install { controller.uiError = it } },
                kind = ButtonKind.Primary, icon = Icons.Rounded.SystemUpdateAlt, busy = controller.updates.installing,
            )
        } else if (error != null) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = SeyirColors.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = SeyirColors.textSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            QuietIconButton(Icons.Rounded.Close, t.close) {
                controller.playback.lastError = null
                controller.downloads.lastError = null
                controller.uiError = null
            }
        }
    }
}

/** Televizyonda çalan içerik: cihaz, başlık, konum ve kumanda düğmeleri. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CastControls(controller: AppController, casting: CastState) {
    val t = LocalStrings.current
    val status = casting.status
    Icon(Icons.Rounded.CastConnected, null, tint = SeyirColors.accent, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Text(
        if (casting.connecting) t.deviceConnecting(casting.device) else casting.device,
        style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted, maxLines = 1,
    )
    Spacer(Modifier.width(10.dp))
    Text(
        casting.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    if (status != null && !casting.connecting) {
        Text(
            listOfNotNull(formatTime(status.position), status.duration?.let(::formatTime)).joinToString(" / "),
            style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted,
        )
        Spacer(Modifier.width(12.dp))
        QuietIconButton(Icons.Rounded.Replay10, t.seekBack10) { controller.cast.seekBy(-10.0) }
        val paused = status.state == "PAUSED"
        QuietIconButton(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) t.resume else t.pause) {
            controller.cast.togglePause()
        }
        QuietIconButton(Icons.Rounded.Forward30, t.seekForward30) { controller.cast.seekBy(30.0) }
        Spacer(Modifier.width(4.dp))
    }
    SeyirButton(t.stop, { controller.cast.stop() }, kind = ButtonKind.Quiet, icon = Icons.Rounded.Stop)
}
