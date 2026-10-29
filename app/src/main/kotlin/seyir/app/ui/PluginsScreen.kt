package seyir.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seyir.app.AppController
import seyir.app.PluginRepository
import seyir.app.RepositoryPlugin
import seyir.app.i18n.LocalStrings
import seyir.app.probeResultLabel
import seyir.app.probeStepLine
import seyir.core.FailureKind
import seyir.core.LoadedPlugin
import seyir.core.ProbeResult
import seyir.core.ProbeStatus
import seyir.core.SeyirLog
import seyir.core.StepStatus
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Bir deponun ekrandaki durumu: okunuyor, okundu ya da hata verdi. */
private data class RepoState(val loading: Boolean = true, val repo: PluginRepository? = null, val error: String? = null)

@Composable
fun PluginsScreen(controller: AppController) {
    val t = LocalStrings.current
    var message by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    // Depolar sekmesinden kuruluma kadar paylaşılan durum; sekme değişince kaybolmasın
    val repos = remember { mutableStateMapOf<String, RepoState>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }

    fun installFromFile() {
        val dialog = FileDialog(null as Frame?, t.chooseFileTitle, FileDialog.LOAD).apply {
            // Windows'ta süzgeç setFile ile, öteki sistemlerde FilenameFilter ile çalışır
            file = "*.jar;*.cs3"
            setFilenameFilter { _, name -> name.endsWith(".jar") || name.endsWith(".cs3") }
            isVisible = true
        }
        val name = dialog.file ?: return
        val file = File(dialog.directory, name)
        controller.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { controller.plugins.installFile(file) } }
            controller.pluginActions.reloadProviders()
            message = result.fold(
                { t.pluginInstalled(it.manifest.name, it.manifest.version ?: 0) },
                { t.installFailed(it.message) },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t.navPlugins, t.pluginsEnabledCount(controller.enabledProviders.size, controller.providers.size)) {
            SeyirButton(t.installFromFile, ::installFromFile, icon = Icons.Rounded.UploadFile)
            SeyirButton(
                t.update,
                {
                    val list = controller.settings.repositories
                    if (list.isEmpty()) {
                        message = t.addRepoToUpdate
                    } else {
                        updating = true
                        controller.scope.launch {
                            val updated = withContext(Dispatchers.IO) { controller.pluginActions.updateNow(list) }
                            message = if (updated.isEmpty()) t.pluginsUpToDate else t.updatedList(updated.joinToString())
                            updating = false
                        }
                    }
                },
                icon = Icons.Rounded.SystemUpdateAlt,
                busy = updating,
            )
        }
        TextTabs(listOf(t.installed, t.tabRepositories), controller.pluginsTab) { controller.pluginsTab = it }
        message?.let {
            Text(
                it,
                Modifier.padding(start = PageGutter, end = PageGutter, top = 14.dp),
                style = MaterialTheme.typography.labelMedium,
                color = SeyirColors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        when (controller.pluginsTab) {
            0 -> InstalledTab(controller) { message = it }
            else -> RepositoriesTab(controller, repos, busy) { message = it }
        }
    }
}

// ---- kurulu eklentiler ----

@Composable
private fun InstalledTab(controller: AppController, onMessage: (String) -> Unit) {
    val t = LocalStrings.current
    var filter by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val plugins = remember(controller.providers) {
        controller.providers.associate { api -> api.name to controller.plugins.plugins.firstOrNull { api in it.apis } }
    }
    Row(Modifier.padding(horizontal = PageGutter), verticalAlignment = Alignment.CenterVertically) {
        SeyirTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = t.filterPlugins,
            leadingIcon = Icons.Rounded.FilterList,
            modifier = Modifier.width(320.dp),
        )
        Spacer(Modifier.width(12.dp))
        SeyirButton(t.checkDomains, {
            controller.pluginActions.refreshDomains(controller.providers)
            onMessage(t.domainsChecking)
        }, kind = ButtonKind.Quiet, icon = Icons.Rounded.Language)
        Spacer(Modifier.width(4.dp))
        val progress = controller.probes.progress
        SeyirButton(
            t.probeAll,
            { controller.probes.probeAll(onMessage) },
            kind = ButtonKind.Quiet,
            icon = Icons.Rounded.PlaylistPlay,
            enabled = progress == null && controller.providers.isNotEmpty(),
            busy = progress != null,
        )
        progress?.let {
            Spacer(Modifier.width(8.dp))
            Text(t.probeRunning(it.first, it.second), style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
        }
        // Yalnız bir sınama kırmızı sonuç verdiyse görünür; kaldırma uygulama içinde onaylanır
        val failing = controller.probes.failingPlugins()
        if (failing.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            SeyirButton(t.removeFailing, { confirmRemove = true }, kind = ButtonKind.Quiet, icon = Icons.Rounded.DeleteOutline, enabled = progress == null)
        }
        if (confirmRemove) {
            ConfirmDialog(
                title = t.removeFailingTitle,
                message = t.removeFailingBody(failing.joinToString { it.manifest.name ?: "?" }),
                confirmLabel = t.remove,
                onConfirm = {
                    confirmRemove = false
                    controller.pluginActions.removeFailingPlugins(onMessage)
                },
                onDismiss = { confirmRemove = false },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    val list = controller.providers.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
    if (list.isEmpty()) {
        EmptyPanel(t.noMatchingPlugin, t.noMatchingPluginHint, Icons.Rounded.FilterList)
        return
    }
    Box(Modifier.fillMaxSize()) {
        val state = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 48.dp),
        ) {
            itemsIndexed(list, key = { _, api -> api.name }) { index, api ->
                val plugin = plugins[api.name]
                // Alan adı yerinde değişince (domainsVersion) satır adresini yeniden okusun
                key(controller.domainsVersion) {
                    PluginRow(
                        controller, api, plugin,
                        removable = plugin != null && controller.plugins.isUserInstalled(plugin),
                        first = index == 0, last = index == list.lastIndex,
                    ) {
                        plugin ?: return@PluginRow
                        controller.scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { controller.plugins.uninstall(plugin) } }
                            controller.pluginActions.reloadProviders()
                            onMessage(result.fold({ t.pluginRemoved(plugin.manifest.name) }, { t.pluginRemoveFailed(it.message) }))
                        }
                    }
                }
            }
        }
        ScrollBar(state)
    }
}

/** Tek bir eklenti satırı; satırlar birleşik bir panel içinde kıl çizgiyle ayrılır. */
@Composable
private fun PluginRow(
    controller: AppController,
    api: MainAPI,
    plugin: LoadedPlugin?,
    removable: Boolean,
    first: Boolean,
    last: Boolean,
    onRemove: () -> Unit,
) {
    val t = LocalStrings.current
    val enabled = api.name !in controller.settings.disabledProviders
    val health = controller.health.records[api.name]
    val result = controller.probes.results[api.name]
    val probing = controller.probes.probing[api.name] == true
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) SeyirColors.surfaceHigh else SeyirColors.surface, tween(140))
    Row(
        Modifier.widthIn(max = 1080.dp).fillMaxWidth().clip(joinedShape(first, last)).background(background)
            .hoverable(interaction)
            .drawBehind {
                if (!last) drawLine(SeyirColors.hairline, Offset(20.dp.toPx(), size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    api.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) SeyirColors.text else SeyirColors.textMuted,
                )
                plugin?.manifest?.version?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("v$it", style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
                }
                Spacer(Modifier.width(12.dp))
                MetaLine(api.supportedTypes.mapNotNull { typeLabel(it.name) }.distinct(), color = SeyirColors.textMuted)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    api.mainUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    t.installedByYou.takeIf { removable },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = SeyirColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Kaynak deposundan çıkarılmış: güncelleme almaz, çoğu zaman site kapandığı için
            if (plugin?.manifest?.name in controller.pluginActions.delisted) {
                Spacer(Modifier.height(3.dp))
                Text(
                    t.pluginDelisted,
                    style = MaterialTheme.typography.labelSmall,
                    color = SeyirColors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            health?.takeIf { !it.ok }?.message?.let { error ->
                Spacer(Modifier.height(3.dp))
                Text(
                    t.lastErrorLabel(error),
                    style = MaterialTheme.typography.labelSmall,
                    color = SeyirColors.danger.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Sınama dökümü: her adımın durumu ve nedeni (durum rozetine tıklayınca açılır)
            if (expanded && result != null) {
                Spacer(Modifier.height(6.dp))
                result.steps.forEach { step ->
                    Text(
                        probeStepLine(t, step),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            // Cloudflare doğrulaması site hatası değil; sarı sonuçla aynı renk
                            step.failure?.kind == FailureKind.CLOUDFLARE -> SeyirColors.accent
                            step.status == StepStatus.FAILED -> SeyirColors.danger.copy(alpha = 0.85f)
                            else -> SeyirColors.textMuted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Sınama sonucu varsa o gösterilir; yoksa çalışanlar sessiz kalır, göze yalnız sorunlu sağlayıcı çarpar
        Spacer(Modifier.width(16.dp))
        when {
            probing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SeyirColors.accent)
            result != null -> ProbeBadge(result, expanded) { expanded = !expanded }
            health != null && !health.ok -> StatusDot(false)
        }
        Spacer(Modifier.width(8.dp))
        SeyirButton(t.probeAction, { controller.probes.probe(api) }, kind = ButtonKind.Quiet, icon = Icons.Rounded.PlayArrow, enabled = !probing)
        if (removable) {
            Spacer(Modifier.width(12.dp))
            QuietIconButton(Icons.Rounded.DeleteOutline, t.removePlugin, size = 36.dp, tint = SeyirColors.textMuted, onClick = onRemove)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                controller.updateSettings { s ->
                    s.copy(disabledProviders = if (on) s.disabledProviders - api.name else s.disabledProviders + api.name)
                }
            },
            colors = seyirSwitchColors(),
        )
    }
}

/** Sınama sonucunun renkli rozeti: yeşil çalışıyor, kehribar kısmen, kırmızı çalışmıyor (Cloudflare doğrulaması bekleyen de kehribar); tıklayınca adım dökümü açılır. */
@Composable
private fun ProbeBadge(result: ProbeResult, expanded: Boolean, onToggle: () -> Unit) {
    val t = LocalStrings.current
    val color = when (result.status) {
        ProbeStatus.GREEN -> SeyirColors.success
        ProbeStatus.YELLOW -> SeyirColors.accent
        ProbeStatus.RED -> SeyirColors.danger
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(probeResultLabel(t, result), style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.width(2.dp))
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            null,
            tint = SeyirColors.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun joinedShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 14.dp else 0.dp, topEnd = if (first) 14.dp else 0.dp,
    bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp,
)

// ---- depolar ----

@Composable
private fun RepositoriesTab(
    controller: AppController,
    repos: SnapshotStateMap<String, RepoState>,
    busy: SnapshotStateMap<String, Boolean>,
    onMessage: (String) -> Unit,
) {
    val t = LocalStrings.current
    val urls = controller.settings.repositories
    var input by remember { mutableStateOf("") }

    fun load(url: String, openPicker: Boolean = false) {
        repos[url] = RepoState()
        controller.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { controller.plugins.fetchRepository(url) } }
            repos[url] = result.fold(
                { RepoState(loading = false, repo = it) },
                { RepoState(loading = false, error = it.message ?: it::class.simpleName) },
            )
            if (openPicker) result.onSuccess { controller.pluginActions.pickerRepository = it }
        }
    }

    controller.pluginActions.pickerRepository?.let { repo ->
        PluginPickerDialog(controller, repo, onDismiss = { controller.pluginActions.pickerRepository = null }) { result ->
            controller.pluginActions.pickerRepository = null
            onMessage(result)
        }
    }

    LaunchedEffect(urls) { urls.filter { it !in repos }.forEach(::load) }

    // source: eklentinin geldiği depo; doğrudan adresten kurulumda null (bir depoya bağlanmaz)
    fun install(entry: RepositoryPlugin, source: String?) {
        busy[entry.internalName] = true
        controller.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { controller.plugins.install(entry, source) } }
            controller.pluginActions.reloadProviders()
            busy.remove(entry.internalName)
            result.onFailure { SeyirLog.w("PluginsScreen", "${entry.internalName} kurulamadı", it) }
            onMessage(result.fold({ t.pluginInstalled(it.manifest.name, it.manifest.version ?: 0) }, { t.pluginInstallFailed(entry.internalName, it.message) }))
        }
    }

    fun add() {
        val value = input.trim()
        if (!value.startsWith("http")) return
        input = ""
        if (value.substringBefore('?').let { it.endsWith(".jar") || it.endsWith(".cs3") }) {
            // Tek eklenti adresi: depo kaydetmeden doğrudan kurulur
            install(RepositoryPlugin(url = value, internalName = value.substringBefore('?').substringAfterLast('/').removeSuffix(".jar").removeSuffix(".cs3")), source = null)
            return
        }
        controller.updateSettings { it.copy(repositories = (it.repositories + value).distinct()) }
        load(value, openPicker = true)
    }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 48.dp),
        ) {
            item {
                Column(Modifier.widthIn(max = 1080.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeyirTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = t.repoPlaceholder,
                            modifier = Modifier.weight(1f),
                            onSubmit = ::add,
                        )
                        Spacer(Modifier.width(10.dp))
                        SeyirButton(t.add, ::add, icon = Icons.Rounded.Add, enabled = input.trim().startsWith("http"))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t.pluginsRunCodeWarning,
                        style = MaterialTheme.typography.labelMedium,
                        color = SeyirColors.textMuted,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
            if (urls.isEmpty()) {
                item {
                    EmptyPanel(
                        t.noRepos,
                        t.noReposHint,
                        Icons.Rounded.Extension,
                    )
                }
            }
            for (url in urls) {
                repoSection(controller, url, repos[url] ?: RepoState(), busy, onRetry = { load(url) }, onInstall = { install(it, url) }, onPick = { controller.pluginActions.pickerRepository = it }) {
                    controller.updateSettings { s -> s.copy(repositories = s.repositories - url) }
                    repos.remove(url)
                }
            }
        }
        ScrollBar(listState)
    }
}

private fun LazyListScope.repoSection(
    controller: AppController,
    url: String,
    state: RepoState,
    busy: Map<String, Boolean>,
    onRetry: () -> Unit,
    onInstall: (RepositoryPlugin) -> Unit,
    onPick: (PluginRepository) -> Unit,
    onRemove: () -> Unit,
) {
    val repo = state.repo
    item(key = "repo:$url") {
        val t = LocalStrings.current
        Row(Modifier.widthIn(max = 1080.dp).fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(repo?.name ?: url, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(repo?.plugins?.size?.let { t.pluginsCount(it) }, repo?.description, url).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = SeyirColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SeyirColors.accent)
                Spacer(Modifier.width(8.dp))
            } else {
                if (repo != null) {
                    SeyirButton(t.selectAndInstall, { onPick(repo) }, kind = ButtonKind.Quiet, icon = Icons.Rounded.Download)
                    Spacer(Modifier.width(4.dp))
                }
                QuietIconButton(Icons.Rounded.Refresh, t.reloadRepo, size = 36.dp, tint = SeyirColors.textMuted, onClick = onRetry)
            }
            QuietIconButton(Icons.Rounded.Close, t.removeRepo, size = 36.dp, tint = SeyirColors.textMuted, onClick = onRemove)
        }
    }
    state.error?.let { error ->
        item(key = "error:$url") {
            Text(
                LocalStrings.current.repoUnreadable(error),
                Modifier.widthIn(max = 1080.dp).padding(bottom = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = SeyirColors.danger,
            )
        }
    }
    val plugins = repo?.plugins.orEmpty()
    plugins.forEachIndexed { index, entry ->
        item(key = "plugin:$url:${entry.internalName}") {
            // Yükleyicinin listesi Compose durumu değil; kurulum/kaldırma sağlayıcı listesini yenilediği için ona bağlanır
            val installed = remember(controller.providers) { controller.plugins.plugins.firstOrNull { it.manifest.name == entry.internalName } }
            RepoPluginRow(entry, installed, busy[entry.internalName] == true, first = index == 0, last = index == plugins.lastIndex) {
                onInstall(entry)
            }
        }
    }
    if (plugins.isNotEmpty()) item(key = "gap:$url") { Spacer(Modifier.height(32.dp)) }
}

@Composable
private fun RepoPluginRow(entry: RepositoryPlugin, installed: LoadedPlugin?, busy: Boolean, first: Boolean, last: Boolean, onInstall: () -> Unit) {
    val t = LocalStrings.current
    val installedVersion = installed?.manifest?.version ?: 0
    val updatable = installed != null && entry.version > installedVersion
    Row(
        Modifier.widthIn(max = 1080.dp).fillMaxWidth().clip(joinedShape(first, last)).background(SeyirColors.surface)
            .drawBehind {
                if (!last) drawLine(SeyirColors.hairline, Offset(20.dp.toPx(), size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name ?: entry.internalName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text("v${entry.version}", style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
                Spacer(Modifier.width(12.dp))
                MetaLine(
                    entry.tvTypes.mapNotNull { typeLabel(it) }.distinct() + listOfNotNull(entry.language?.uppercase()),
                    color = SeyirColors.textMuted,
                )
            }
            entry.description?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (entry.authors.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(entry.authors.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
            }
        }
        Spacer(Modifier.width(16.dp))
        when {
            installed != null && !updatable -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = SeyirColors.textMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.installed, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
            }
            updatable -> SeyirButton(t.updateToVersion(installedVersion, entry.version), onInstall, kind = ButtonKind.Primary, icon = Icons.Rounded.SystemUpdateAlt, busy = busy)
            else -> SeyirButton(t.install, onInstall, icon = Icons.Rounded.Download, busy = busy)
        }
    }
}

/** Anahtarın uygulama renkleri: açıkken açık gri iz ve koyu düğme; kehribar birincil eyleme kalır. */
@Composable
fun seyirSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = SeyirColors.background,
    checkedTrackColor = SeyirColors.textSecondary,
    checkedBorderColor = SeyirColors.textSecondary,
    uncheckedThumbColor = SeyirColors.textMuted,
    uncheckedTrackColor = SeyirColors.surfaceHigh,
    uncheckedBorderColor = SeyirColors.outline,
)
