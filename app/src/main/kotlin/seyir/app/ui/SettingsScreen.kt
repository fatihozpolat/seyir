package seyir.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import seyir.app.AppController
import seyir.app.i18n.I18n
import seyir.app.i18n.LocalStrings
import seyir.core.AppDirs
import seyir.core.hls.QualityPreference
import seyir.core.net.DohProvider
import java.awt.Desktop

@Composable
fun SettingsScreen(controller: AppController) {
    val t = LocalStrings.current
    val s = controller.settings
    var proxy by remember { mutableStateOf(s.proxy) }
    var mpvPath by remember { mutableStateOf(s.mpvPath) }
    var browserPath by remember { mutableStateOf(s.browserPath) }
    var subtitleLang by remember { mutableStateOf(s.subtitleLang) }
    var castAddress by remember { mutableStateOf(s.castAddress) }
    var githubToken by remember { mutableStateOf(s.githubToken) }
    var tmdbApiKey by remember { mutableStateOf(s.tmdbApiKey) }
    var saved by remember { mutableStateOf(false) }
    val dirty = proxy != s.proxy || mpvPath != s.mpvPath || browserPath != s.browserPath || subtitleLang != s.subtitleLang ||
        castAddress != s.castAddress || githubToken != s.githubToken || tmdbApiKey != s.tmdbApiKey

    fun save() {
        controller.updateSettings {
            it.copy(
                proxy = proxy.trim(),
                mpvPath = mpvPath.trim(),
                browserPath = browserPath.trim(),
                subtitleLang = subtitleLang.trim().ifBlank { "tr" },
                castAddress = castAddress.trim(),
                githubToken = githubToken.trim(),
                tmdbApiKey = tmdbApiKey.trim(),
            )
        }
        saved = true
    }

    Box(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // Başlık satırı içerik sütunuyla aynı genişlikte; Kaydet panellerin sağ kenarına oturur
            Box(Modifier.widthIn(max = 920.dp + PageGutter * 2)) { ScreenHeader(t.navSettings) {
                if (saved && !dirty) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, null, tint = SeyirColors.success, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t.saved, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
                    }
                }
                SeyirButton(t.save, ::save, kind = ButtonKind.Primary, enabled = dirty)
            } }
            Column(Modifier.padding(horizontal = PageGutter).widthIn(max = 920.dp)) {
                Section(t.sectionGeneral) {
                    ChoiceRow(
                        t.languageTitle,
                        t.languageHint,
                        options = listOf("system" to t.languageSystem, "tr" to t.languageTurkish, "en" to t.languageEnglish),
                        selected = s.language,
                    ) { value -> controller.updateSettings { it.copy(language = value) } }
                }

                Section(t.sectionNetwork) {
                    ToggleRow(
                        t.fragmentTitle,
                        t.fragmentHint,
                        s.fragmentTls,
                    ) { on -> controller.updateSettings { it.copy(fragmentTls = on) } }
                    Divider()
                    ChoiceRow(
                        t.dnsTitle,
                        t.dnsHint,
                        options = listOf("NONE" to t.systemDns) + DohProvider.entries.map { it.name to dohLabel(it) },
                        selected = s.doh,
                    ) { value -> controller.updateSettings { it.copy(doh = value) } }
                    Divider()
                    FieldRow(
                        t.proxyTitle,
                        t.proxyHint,
                        proxy,
                        placeholder = t.proxyPlaceholder,
                    ) { proxy = it; saved = false }
                }

                Section(t.sectionPlayer) {
                    FieldRow(
                        t.mpvPathTitle,
                        t.mpvPathHint,
                        mpvPath,
                        detail = controller.playback.playerInfo,
                        placeholder = t.automatic,
                    ) { mpvPath = it; saved = false }
                    Divider()
                    ToggleRow(
                        t.embeddedPlayerTitle,
                        t.embeddedPlayerHint,
                        s.embeddedPlayer,
                    ) { on -> controller.updateSettings { it.copy(embeddedPlayer = on) } }
                    // uosc arayüzü yalnız ayrı mpv penceresinde kullanılır
                    if (!s.embeddedPlayer) {
                        Divider()
                        ToggleRow(
                            t.playerUiTitle,
                            t.playerUiHint,
                            s.playerUi,
                        ) { on -> controller.updateSettings { it.copy(playerUi = on) } }
                    }
                    Divider()
                    FieldRow(t.subtitleLangTitle, t.subtitleLangHint, subtitleLang) {
                        subtitleLang = it; saved = false
                    }
                    Divider()
                    ChoiceRow(
                        t.subtitleSizeTitle,
                        t.subtitleSizeHint,
                        options = subtitleSizes(t).map { (scale, label) -> scale.toString() to label },
                        selected = subtitleSizes(t).minBy { kotlin.math.abs(it.first - s.subtitleScale) }.first.toString(),
                    ) { value -> controller.updateSettings { it.copy(subtitleScale = value.toDouble()) } }
                    Divider()
                    ToggleRow(
                        t.autoPlayTitle,
                        t.autoPlayHint,
                        s.autoPlayBest,
                    ) { on -> controller.updateSettings { it.copy(autoPlayBest = on) } }
                    Divider()
                    ToggleRow(
                        t.autoplayNextTitle,
                        t.autoplayNextHint,
                        s.autoplayNext,
                    ) { on -> controller.updateSettings { it.copy(autoplayNext = on) } }
                    Divider()
                    ChoiceRow(
                        t.defaultQualityTitle,
                        t.defaultQualityHint,
                        options = QualityPreference.entries.map { it.id to qualityPreferenceLabel(it) },
                        selected = QualityPreference.fromId(s.defaultQuality).id,
                    ) { value -> controller.updateSettings { it.copy(defaultQuality = value) } }
                    Divider()
                    FieldRow(
                        t.tvAddressTitle,
                        t.tvAddressHint,
                        castAddress,
                        placeholder = t.tvAddressPlaceholder,
                    ) { castAddress = it; saved = false }
                }

                Section(t.sectionCloudflare) {
                    ToggleRow(
                        t.solveChallengesTitle,
                        t.solveChallengesHint,
                        s.solveCloudflare,
                    ) { on -> controller.updateSettings { it.copy(solveCloudflare = on) } }
                    Divider()
                    FieldRow(
                        t.browserPathTitle,
                        t.browserPathHint,
                        browserPath,
                        detail = controller.playback.browserInfo,
                        placeholder = t.automatic,
                    ) { browserPath = it; saved = false }
                }

                Section(t.navPlugins) {
                    ToggleRow(
                        t.autoUpdatePluginsTitle,
                        t.autoUpdatePluginsHint,
                        s.autoUpdatePlugins,
                    ) { on -> controller.updateSettings { it.copy(autoUpdatePlugins = on) } }
                    Divider()
                    ToggleRow(
                        t.autoInstallNewPluginsTitle,
                        t.autoInstallNewPluginsHint,
                        s.autoInstallNewPlugins,
                    ) { on -> controller.updateSettings { it.copy(autoInstallNewPlugins = on) } }
                    Divider()
                    FieldRow(
                        t.githubTokenTitle,
                        t.githubTokenHint,
                        githubToken,
                        placeholder = t.githubTokenPlaceholder,
                        secret = true,
                    ) { githubToken = it; saved = false }
                }

                Section(t.sectionMetadata) {
                    FieldRow(
                        t.tmdbKeyTitle,
                        t.tmdbKeyHint,
                        tmdbApiKey,
                        placeholder = t.tmdbKeyPlaceholder,
                        secret = true,
                    ) { tmdbApiKey = it; saved = false }
                }

                Section(t.sectionApp) {
                    var updateMessage by remember { mutableStateOf<String?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabelColumn(
                            "Seyir ${controller.updates.currentVersion}",
                            updateMessage ?: t.releasesHint(seyir.app.UpdateService.REPOSITORY),
                            Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        SeyirButton(
                            t.checkForUpdates,
                            { controller.updates.check { updateMessage = it } },
                            icon = Icons.Rounded.SystemUpdateAlt,
                            busy = controller.updates.checking,
                        )
                    }
                    Divider()
                    ToggleRow(
                        t.checkOnStartupTitle,
                        t.checkOnStartupHint,
                        s.checkAppUpdates,
                    ) { on -> controller.updateSettings { it.copy(checkAppUpdates = on) } }
                    Divider()
                    ChoiceRow(
                        t.logLevelTitle,
                        t.logLevelHint,
                        options = listOf(
                            "error" to t.logLevelError,
                            "warn" to t.logLevelWarn,
                            "info" to t.logLevelInfo,
                            "debug" to t.logLevelDebug,
                        ),
                        selected = s.logLevel,
                    ) { value -> controller.updateSettings { it.copy(logLevel = value) } }
                    Divider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabelColumn(t.logFolderTitle, t.logFolderHint, Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        SeyirButton(t.openLogFolder, { openFolder(AppDirs.data) }, icon = Icons.Rounded.FolderOpen)
                    }
                }

                Section(t.sectionData) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabelColumn(t.dataFolder, AppDirs.data.absolutePath, Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        SeyirButton(t.openFolder, { openFolder(AppDirs.data) }, icon = Icons.Rounded.FolderOpen)
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
        ScrollBar(scroll)
    }
}

/** Klasörü sistemin dosya yöneticisinde açar; `Desktop` yoksa (bazı Linux ortamları) `xdg-open` denenir. */
private fun openFolder(dir: java.io.File) {
    val opened = runCatching {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
        Desktop.getDesktop().open(dir)
    }.isSuccess
    if (!opened) runCatching { ProcessBuilder("xdg-open", dir.absolutePath).start() }
}

private fun qualityPreferenceLabel(preference: QualityPreference) = when (preference) {
    QualityPreference.HIGHEST -> I18n.current.qualityHighest
    QualityPreference.P1080 -> "1080p"
    QualityPreference.P720 -> "720p"
    QualityPreference.P480 -> "480p"
    QualityPreference.LOWEST -> I18n.current.qualityDataSaver
}

private fun dohLabel(provider: DohProvider) = when (provider) {
    DohProvider.CLOUDFLARE -> "Cloudflare (1.1.1.1)"
    DohProvider.GOOGLE -> "Google (8.8.8.8)"
    DohProvider.ADGUARD -> I18n.current.dohAdguard
}

/** Başlıklı ayar grubu: başlık panelin üstünde, satırlar panelin içinde. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
        Column(
            Modifier.fillMaxWidth().clip(shape).background(SeyirColors.surface)
                .border(1.dp, SeyirColors.hairline, shape)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            content = { content() },
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 16.dp), thickness = 1.dp, color = SeyirColors.hairline)
}

@Composable
private fun LabelColumn(title: String, hint: String?, modifier: Modifier, detail: String? = null) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = SeyirColors.textMuted)
        }
        // Uzun dosya yolu kelime ortasından kırılmasın diye tek satırda, ortadan kısaltılmış
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                middleEllipsis(detail, 72),
                style = MaterialTheme.typography.labelSmall,
                color = SeyirColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun middleEllipsis(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max / 2 - 1) + "…" + text.takeLast(max / 2 - 1)

@Composable
private fun ToggleRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Bütün satır tek bir anahtar gibi odaklanır (Enter/Boşluk çevirir) ve belirgin odak halkası alır;
    // Switch'in kendisi yalnız görsel kalır, böylece Tab iki kez durmaz
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.clip(shape)
            .focusRing(interaction, shape)
            .toggleable(checked, interaction, indication = null, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelColumn(title, hint, Modifier.weight(1f))
        Spacer(Modifier.width(24.dp))
        Switch(checked, null, colors = seyirSwitchColors())
    }
}

@Composable
private fun FieldRow(
    title: String,
    hint: String?,
    value: String,
    placeholder: String? = null,
    detail: String? = null,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LabelColumn(title, hint, Modifier.weight(1f), detail)
        Spacer(Modifier.width(24.dp))
        SeyirTextField(value = value, onValueChange = onChange, placeholder = placeholder, modifier = Modifier.width(340.dp), secret = secret)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    hint: String?,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LabelColumn(title, hint, Modifier.weight(1f))
        Spacer(Modifier.width(24.dp))
        Box {
            Row(
                Modifier.width(340.dp).height(42.dp).clip(shape)
                    .background(if (hovered) SeyirColors.surfaceHigh else SeyirColors.surface)
                    .border(1.dp, if (hovered) SeyirColors.outline else SeyirColors.hairline, shape)
                    .focusRing(interaction, shape)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, role = Role.DropdownList) { open = true }
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.firstOrNull { it.first == selected }?.second ?: selected,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Rounded.ExpandMore, null, tint = SeyirColors.textMuted)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.width(340.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = SeyirColors.surfaceHigh,
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = if (value == selected) {
                            { Icon(Icons.Rounded.Check, null, tint = SeyirColors.accent, modifier = Modifier.size(18.dp)) }
                        } else null,
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}
