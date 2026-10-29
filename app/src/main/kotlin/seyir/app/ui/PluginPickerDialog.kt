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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import seyir.app.AppController
import seyir.app.PluginRepository
import seyir.app.RepositoryPlugin
import seyir.app.i18n.LocalStrings
import seyir.core.CompatibilityReport

/**
 * Depodaki eklentileri seçerek kurma penceresi: depo eklenince eklentiler olduğu gibi kurulmaz, kullanıcı
 * hangilerini istediğini işaretler. Başlangıçta hiçbiri seçili değildir; kurulu olanlar işaretlenmiş ve
 * kilitli görünür, yeni sürümü olanlar "güncellenebilir" diye seçilebilir. Kurulum bitince pencere kapanır.
 */
@Composable
fun PluginPickerDialog(
    controller: AppController,
    repository: PluginRepository,
    onDismiss: () -> Unit,
    onFinished: (String) -> Unit,
) {
    val t = LocalStrings.current
    val selected = remember(repository) { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    val installing = progress != null

    // Kurulu sürümler pencere açılırken okunur (kurulum bitince pencere zaten kapanır)
    val installedVersions = remember(repository) {
        repository.plugins.associate { it.internalName to controller.plugins.installedVersion(it.internalName) }
    }
    fun isLocked(entry: RepositoryPlugin): Boolean {
        val installed = installedVersions[entry.internalName] ?: return false
        return installed >= entry.version
    }

    // Statik uyumluluk denetimi: pencere açılınca .cs3 adresleri arka planda indirilip çevrilir ve sınıfları denetlenir
    // (en çok 3 eş zamanlı). Seyir'de çalışmayanlar işaretlenir ve seçilemez; denetlenemeyenler (ağ hatası) seçilebilir kalır.
    val compat = remember(repository) { mutableStateMapOf<String, CompatibilityReport>() }
    var compatProgress by remember(repository) { mutableStateOf<Pair<Int, Int>?>(null) }
    fun incompatibility(entry: RepositoryPlugin): CompatibilityReport? = compat[entry.internalName]?.takeIf { !it.compatible }
    LaunchedEffect(repository) {
        val targets = repository.plugins.filter { !isLocked(it) && it.url.substringBefore('?').endsWith(".cs3", ignoreCase = true) }
        val pending = targets.filter { entry ->
            controller.plugins.cachedCompatibility(entry)?.also { compat[entry.internalName] = it } == null
        }
        if (pending.isEmpty()) return@LaunchedEffect
        var done = 0
        compatProgress = 0 to pending.size
        try {
            val gate = Semaphore(3)
            coroutineScope {
                pending.forEach { entry ->
                    launch {
                        gate.withPermit {
                            runCatching { controller.plugins.checkCompatibility(entry) }
                                .onFailure { if (it is CancellationException) throw it }
                                .onSuccess { report ->
                                    compat[entry.internalName] = report
                                    if (!report.compatible) selected.remove(entry.internalName)
                                }
                        }
                        done++
                        compatProgress = done to pending.size
                    }
                }
            }
        } finally {
            compatProgress = null
        }
    }

    val selectable = repository.plugins.filter { !isLocked(it) && incompatibility(it) == null }
    val shown = repository.plugins.filter { entry ->
        filter.isBlank() || (entry.name ?: entry.internalName).contains(filter.trim(), ignoreCase = true) ||
            entry.internalName.contains(filter.trim(), ignoreCase = true)
    }

    fun install() {
        val chosen = repository.plugins.filter { it.internalName in selected && incompatibility(it) == null }
        if (chosen.isEmpty() || installing) return
        progress = ""
        controller.pluginActions.installFromRepository(repository, chosen, onProgress = { progress = it }) { message ->
            progress = null
            onFinished(message)
        }
    }

    Dialog(onDismissRequest = { if (!installing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.width(720.dp)
                .shadow(32.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(SeyirColors.surface)
                .border(1.dp, SeyirColors.hairline, shape)
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(repository.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            t.pickerSubtitle(repository.plugins.size, installedVersions.values.count { it != null }),
                            repository.description,
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = SeyirColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QuietIconButton(Icons.Rounded.Close, t.close, enabled = !installing, onClick = onDismiss)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeyirTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = t.filterPlugins,
                    leadingIcon = Icons.Rounded.FilterList,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                val allSelected = selectable.isNotEmpty() && selectable.all { it.internalName in selected }
                SeyirButton(
                    if (allSelected) t.selectNone else t.selectAll,
                    {
                        if (allSelected) selected.clear()
                        else selectable.map { it.internalName }.filter { it !in selected }.let(selected::addAll)
                    },
                    kind = ButtonKind.Quiet,
                    enabled = !installing && selectable.isNotEmpty(),
                )
            }
            compatProgress?.let { (done, total) ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = SeyirColors.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(t.compatChecking(done, total), style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = SeyirColors.hairline)
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    items(shown, key = { it.internalName }) { entry ->
                        PickerRow(
                            entry = entry,
                            installedVersion = installedVersions[entry.internalName],
                            locked = isLocked(entry),
                            incompatible = incompatibility(entry),
                            checked = isLocked(entry) || entry.internalName in selected,
                            enabled = !installing,
                        ) {
                            if (entry.internalName in selected) selected.remove(entry.internalName) else selected.add(entry.internalName)
                        }
                    }
                }
                ScrollBar(listState)
            }
            HorizontalDivider(thickness = 1.dp, color = SeyirColors.hairline)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progress?.ifBlank { null } ?: if (selected.isEmpty()) t.pickerEmptySelection else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = SeyirColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SeyirButton(t.cancel, onDismiss, kind = ButtonKind.Quiet, enabled = !installing)
                Spacer(Modifier.width(10.dp))
                SeyirButton(
                    t.installSelected(selected.size),
                    ::install,
                    kind = ButtonKind.Primary,
                    icon = Icons.Rounded.Download,
                    enabled = selected.isNotEmpty(),
                    busy = installing,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    entry: RepositoryPlugin,
    installedVersion: Int?,
    locked: Boolean,
    incompatible: CompatibilityReport?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val t = LocalStrings.current
    // Kurulu ya da Seyir'de çalışmayan satırlar seçilemez
    val blocked = locked || incompatible != null
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    val background by animateColorAsState(if (hovered && !blocked) SeyirColors.surfaceHigh else Color.Transparent, tween(120))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(background)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled && !blocked, role = Role.Checkbox, onClick = onToggle)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled && !blocked,
            colors = CheckboxDefaults.colors(
                checkedColor = SeyirColors.accent,
                checkmarkColor = SeyirColors.onAccent,
                uncheckedColor = SeyirColors.outline,
                disabledCheckedColor = SeyirColors.surfaceHover,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name ?: entry.internalName, style = MaterialTheme.typography.labelLarge, color = if (blocked) SeyirColors.textMuted else SeyirColors.text)
                Spacer(Modifier.width(8.dp))
                Text("v${entry.version}", style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
                Spacer(Modifier.width(10.dp))
                MetaLine(entry.tvTypes.mapNotNull { typeLabel(it) }.distinct() + listOfNotNull(entry.language?.uppercase()), color = SeyirColors.textMuted)
            }
            if (incompatible != null) {
                // Neden ikinci satırda: Android kaynakları isteniyorsa o, yoksa eksik sınıfların ilk birkaçı
                Spacer(Modifier.height(2.dp))
                Text(
                    if (incompatible.requiresResources && incompatible.missingClasses.isEmpty()) t.needsResourcesLine
                    else t.missingClassesLine(incompatible.describeMissing()),
                    style = MaterialTheme.typography.labelSmall,
                    color = SeyirColors.danger.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else entry.description?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            incompatible != null -> Text(t.incompatibleBadge, style = MaterialTheme.typography.labelSmall, color = SeyirColors.danger)
            locked -> Text(t.installed, style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
            installedVersion != null -> Chip(t.updatable, highlighted = true)
        }
    }
}
