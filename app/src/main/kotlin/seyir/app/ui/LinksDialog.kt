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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seyir.app.AppController
import seyir.app.PlayContext
import seyir.app.i18n.I18n
import seyir.app.userMessage
import seyir.app.i18n.LocalStrings
import seyir.core.cast.CastDevice
import seyir.core.hls.HlsVariant
import seyir.core.hls.QualityTarget

/** Bir öğenin kaynaklarını (bağlantılarını) bulur ve listeler; seçilen bağlantı oynatılır. */
@Composable
fun LinksDialog(controller: AppController, context: PlayContext, onDismiss: () -> Unit) {
    val t = LocalStrings.current
    val links = remember(context) { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember(context) { mutableStateListOf<SubtitleFile>() }
    var running by remember(context) { mutableStateOf(true) }
    var error by remember(context) { mutableStateOf<String?>(null) }
    var autoStarted by remember(context) { mutableStateOf(false) }

    // M3U8 bağlantıların kalite katmanları (bağlantı adresine göre); yalnız birden çok katman varsa girer
    val layers = remember(context) { mutableStateMapOf<String, List<HlsVariant>>() }

    fun play(link: ExtractorLink, quality: QualityTarget? = null) {
        // Açılamazsa pencerede görünen sıradaki kaynaklar sırayla denenir
        controller.playback.play(context, link, subtitles.toList(), alternatives = orderedLinks(links.toList()), quality = quality)
        onDismiss()
    }

    // Seçilen katmanla oynat: en yüksek katman "en yüksek" olarak, diğerleri "bu yüksekliği aşmayan" olarak sürer
    fun playLayer(link: ExtractorLink, layer: HlsVariant) {
        val top = layers[link.url]?.firstOrNull()
        play(link, if (layer == top) QualityTarget.Highest else QualityTarget.MaxHeight(layer.effectiveHeight))
    }

    fun cast(link: ExtractorLink, device: CastDevice) {
        controller.cast.start(device, context, link, subtitles.toList())
        onDismiss()
    }

    // Televizyon menüsü açıldığında liste hazır olsun diye arama pencereyle birlikte başlar
    LaunchedEffect(Unit) { controller.cast.scan() }

    LaunchedEffect(context) {
        val api = controller.provider(context.provider) ?: run {
            error = t.providerNotInstalled
            running = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                api.loadLinks(
                    context.item.data,
                    false,
                    { sub -> synchronized(subtitles) { if (subtitles.none { it.url == sub.url }) subtitles.add(sub) } },
                    { link -> synchronized(links) { if (links.none { it.url == link.url }) links.add(link) } },
                )
            }
        }
        running = false
        result.onFailure { if (links.isEmpty()) error = userMessage(it, t) }
        if (links.isEmpty() && error == null) error = t.noLinksFound
    }

    // Gelen M3U8 bağlantıların katmanları arka planda getirilir (eşzamanlı istek sınırı getiricide);
    // hata ya da tek katmanda satır olduğu gibi kalır
    LaunchedEffect(context) {
        val requested = HashSet<String>()
        snapshotFlow { links.toList() }.collect { current ->
            for (link in current) {
                if (link.type != ExtractorLinkType.M3U8 || !requested.add(link.url)) continue
                launch {
                    val found = runCatching { controller.playback.layersOf(link, context.provider) }.getOrDefault(emptyList())
                    if (found.size >= 2) layers[link.url] = found
                }
            }
        }
    }

    // Ayarlarda açıksa en iyi bağlantı kendiliğinden oynatılır: arama bitince ya da ilk bağlantı
    // geldikten 8 sn sonra (yavaş bir kaynak bütün listeyi bekletmesin)
    LaunchedEffect(running, links.isNotEmpty()) {
        if (!controller.settings.autoPlayBest || autoStarted || links.isEmpty()) return@LaunchedEffect
        if (running) kotlinx.coroutines.delay(AUTOPLAY_GRACE_MS)
        if (!autoStarted) {
            orderedLinks(links.toList()).firstOrNull()?.let {
                autoStarted = true
                play(it)
            }
        }
    }

    // Bölüme çift tıklayınca ikinci tıklama yeni açılan pencerenin dışına düşüp onu hemen kapatıyordu;
    // açılıştan sonraki kısa süre içinde gelen "dışarı tıklama" kapatma sayılmaz (Kapat düğmesi her zaman çalışır)
    val openedAt = remember(context) { System.currentTimeMillis() }
    Dialog(
        onDismissRequest = { if (System.currentTimeMillis() - openedAt >= DISMISS_GUARD_MS) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.width(660.dp)
                .shadow(32.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(SeyirColors.surface)
                .border(1.dp, SeyirColors.hairline, shape)
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(
                    context.posterUrl,
                    Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayTitle(context.showName), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (context.item.title != context.showName) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            context.item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SeyirColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = SeyirColors.accent)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                running -> t.sourcesSearching(links.size)
                                else -> t.sourcesSummary(links.size, subtitles.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = SeyirColors.textMuted,
                        )
                    }
                }
                QuietIconButton(Icons.Rounded.Close, t.close, onClick = onDismiss)
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(thickness = 1.dp, color = SeyirColors.hairline)
            Spacer(Modifier.height(10.dp))
            val shown = orderedLinks(links.toList())
            if (shown.isEmpty() && running) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text(t.extractingLinks, color = SeyirColors.textMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Aynı bağlantı birden çok çıkarıcıdan gelebiliyor; tekrarı göstermenin anlamı yok
                items(shown, key = { it.url }) { link ->
                    LinkRow(
                        link,
                        best = link == shown.firstOrNull() && qualityOf(link) > 0,
                        onPlay = { play(link) },
                        layers = layers[link.url].orEmpty(),
                        onPlayLayer = { layer -> playLayer(link, layer) },
                        castMenu = if (link.type in CASTABLE) {
                            { expanded, onClose -> CastMenu(controller, expanded, onClose) { device -> cast(link, device) } }
                        } else {
                            null
                        },
                        onDownload = {
                            controller.downloads.start(context, link)
                            onDismiss()
                        },
                    )
                }
            }
            error?.let {
                Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = SeyirColors.danger, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = SeyirColors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private const val AUTOPLAY_GRACE_MS = 8_000L

/** Kaynaklar penceresi açıldıktan sonra dışarı tıklamanın kapatma sayılmadığı süre (çift tıklama aralığı). */
private const val DISMISS_GUARD_MS = 600L

private val CASTABLE = setOf(ExtractorLinkType.M3U8, ExtractorLinkType.DASH, ExtractorLinkType.VIDEO)

/** Ağda bulunan televizyonlar; seçilen cihazda bağlantı oynatılır. */
@Composable
private fun CastMenu(controller: AppController, expanded: Boolean, onClose: () -> Unit, onPick: (CastDevice) -> Unit) {
    val t = LocalStrings.current
    val cast = controller.cast
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onClose,
        shape = RoundedCornerShape(12.dp),
        containerColor = SeyirColors.surfaceHigh,
    ) {
        Text(
            t.playOnTv,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = SeyirColors.textMuted,
        )
        cast.devices.forEach { device ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(device.name, style = MaterialTheme.typography.labelLarge)
                        device.model?.takeIf { it != device.name }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = SeyirColors.textMuted)
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Rounded.Tv, null, tint = SeyirColors.textSecondary) },
                onClick = {
                    onClose()
                    onPick(device)
                },
            )
        }
        if (cast.scanning) {
            DropdownMenuItem(
                text = { Text(t.searching, color = SeyirColors.textMuted) },
                leadingIcon = { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SeyirColors.accent) },
                onClick = {},
                enabled = false,
            )
        } else {
            if (cast.devices.isEmpty()) {
                Text(
                    t.noTvFound,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(300.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = SeyirColors.textSecondary,
                )
            }
            DropdownMenuItem(
                text = { Text(t.scanAgain) },
                leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = SeyirColors.textMuted) },
                onClick = { cast.scan(force = true) },
            )
        }
    }
}

@Composable
private fun LinkRow(
    link: ExtractorLink,
    best: Boolean,
    onPlay: () -> Unit,
    layers: List<HlsVariant>,
    onPlayLayer: (HlsVariant) -> Unit,
    castMenu: (@Composable (expanded: Boolean, onClose: () -> Unit) -> Unit)?,
    onDownload: () -> Unit,
) {
    val t = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    val background by animateColorAsState(if (hovered) SeyirColors.surfaceHigh else Color.Transparent, tween(120))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(background)
            .focusRing(interaction, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onPlay)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (hovered || best) SeyirColors.accent else SeyirColors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PlayArrow, null,
                tint = if (hovered || best) SeyirColors.onAccent else SeyirColors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(link.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(link.source.takeIf { it != link.name }, typeName(link.type.name), hostOf(link.url)).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = SeyirColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (layers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    layers.take(MAX_LAYER_CHIPS).forEach { layer ->
                        LayerChip(layer.label, t.qualityChipHint(layer.label)) { onPlayLayer(layer) }
                    }
                }
            }
        }
        val quality = qualityOf(link)
        qualityLabel(quality)?.let {
            Spacer(Modifier.width(10.dp))
            Chip(it, highlighted = quality >= Qualities.P1080.value)
        }
        Spacer(Modifier.width(4.dp))
        if (castMenu != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                QuietIconButton(Icons.Rounded.Cast, t.playOnTv, size = 36.dp, tint = SeyirColors.textMuted) { menu = true }
                castMenu(menu) { menu = false }
            }
        }
        QuietIconButton(Icons.Rounded.Download, t.download, size = 36.dp, tint = SeyirColors.textMuted, onClick = onDownload)
    }
}

private const val MAX_LAYER_CHIPS = 6

/** Tıklanabilir katman çipi; [Chip] ile aynı biçim, üzerine gelince vurgu rengine döner. */
@Composable
private fun LayerChip(text: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val background by animateColorAsState(if (hovered) SeyirColors.accent.copy(alpha = 0.16f) else SeyirColors.surfaceHigh, tween(120))
    Box(
        Modifier.clip(shape).background(background)
            .focusRing(interaction, shape)
            .hoverable(interaction)
            .semantics { contentDescription = description }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (hovered) SeyirColors.accent else SeyirColors.textSecondary,
            maxLines = 1,
        )
    }
}

private fun typeName(type: String) = when (type) {
    "M3U8" -> "HLS"
    "VIDEO" -> I18n.current.linkTypeFile
    else -> type
}

private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull()

private fun qualityLabel(quality: Int): String? = linkQualityLabel(quality)

/** Bağlantı kalitesinin kısa adı ("1080p", "4K"); bilinmiyorsa null. */
internal fun linkQualityLabel(quality: Int): String? = when {
    quality <= 0 || quality == Qualities.Unknown.value -> null
    quality >= 2160 -> "4K"
    else -> "${quality}p"
}
