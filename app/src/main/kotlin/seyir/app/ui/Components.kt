package seyir.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.launch
import seyir.app.i18n.I18n
import seyir.app.i18n.LocalStrings

/** Ağdan görsel yükler; adres boşsa ya da yüklenemezse yer tutucu gösterir. */
@Composable
fun Poster(
    url: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    alignment: Alignment = Alignment.Center,
) {
    val context = LocalPlatformContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (!headers.isNullOrEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                }
            }
            .build()
    }
    Box(modifier.background(SeyirColors.surface), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            PosterPlaceholder()
        } else {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
                error = { PosterPlaceholder() },
                loading = { PosterPlaceholder() },
            )
        }
    }
}

@Composable
private fun PosterPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(SeyirColors.surfaceHigh, SeyirColors.surface))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Movie, null, tint = SeyirColors.outline, modifier = Modifier.size(32.dp))
    }
}

/**
 * Pano/detay arka planı: görsel zemine sol ve alt kenardan erir. Yatay arka plan görseli yoksa
 * afiş bulanıklaştırılıp renk alanı olarak kullanılır.
 */
@Composable
fun Backdrop(url: String?, blurred: Boolean, headers: Map<String, String>? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    val ground = SeyirColors.background
    Box(modifier.background(ground)) {
        if (!url.isNullOrBlank()) {
            Poster(
                url,
                Modifier.fillMaxSize()
                    .then(if (blurred) Modifier.blur(40.dp).graphicsLayer { alpha = 0.72f } else Modifier.graphicsLayer { alpha = 0.9f }),
                headers,
                alignment = Alignment.TopCenter,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to ground, 0.28f to ground.copy(alpha = 0.9f), 0.6f to ground.copy(alpha = 0.25f), 0.85f to Color.Transparent,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to ground.copy(alpha = 0.6f), 0.22f to Color.Transparent, 0.55f to Color.Transparent, 1f to ground,
                )
            )
        )
    }
}

/** Klavyeyle odaklanınca kehribar halka çizer (fareyle tıklamada görünmez). */
@Composable
fun Modifier.focusRing(interaction: MutableInteractionSource, shape: Shape, width: Dp = 2.dp): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) border(width, SeyirColors.accent, shape) else this
}

val PosterShape = RoundedCornerShape(10.dp)

/**
 * Dikey afiş kartı: 2:3 görsel, altında başlık; üzerine gelince hafifçe büyür. [onPlay] verilirse
 * üzerine gelince sağ altta oynat düğmesi çıkar (ayrıntıya gitmeden oynatma); [playBusy] iken düğmede
 * bekleme göstergesi döner.
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    subtitle: String? = null,
    progress: Float? = null,
    width: Dp = 160.dp,
    posterHeaders: Map<String, String>? = null,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    playBusy: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val description = displayTitle(title)
    val scale by animateFloatAsState(if (hovered) 1.035f else 1f, tween(220))
    val border by animateColorAsState(
        if (hovered) Color.White.copy(alpha = 0.28f) else SeyirColors.hairline, tween(220)
    )
    Column(
        modifier.width(width)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            // Ekran okuyucu afişi başlığıyla okur (görselin kendisinin açıklaması yok)
            .semantics { contentDescription = description }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(if (hovered) 18.dp else 8.dp, PosterShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(PosterShape)
                .focusRing(interaction, PosterShape, 3.dp)
        ) {
            Poster(posterUrl, Modifier.fillMaxSize(), posterHeaders)
            Box(Modifier.fillMaxSize().border(1.dp, border, PosterShape))
            if (progress != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                )
                ProgressBar(
                    progress,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp),
                    track = Color.White.copy(alpha = 0.25f),
                )
            }
            if (onPlay != null && (hovered || playBusy)) {
                CardPlayButton(
                    busy = playBusy,
                    onClick = onPlay,
                    // İlerleme çubuğu varsa onun üstünde durur
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = if (progress != null) 22.dp else 10.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            displayTitle(title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (hovered) SeyirColors.text else SeyirColors.textSecondary,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = SeyirColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Afiş kartının sağ altındaki yuvarlak oynat düğmesi; karta tıklamadan ayrı çalışır. */
@Composable
private fun CardPlayButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.08f else 1f, tween(140))
    Box(
        modifier.size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(10.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(SeyirColors.accent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = t.play },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SeyirColors.onAccent)
        } else {
            Icon(Icons.Rounded.PlayArrow, null, tint = SeyirColors.onAccent, modifier = Modifier.size(24.dp))
        }
    }
}

/** İnce, yuvarlak uçlu ilerleme çubuğu. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, track: Color = SeyirColors.surfaceHover) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(SeyirColors.accent))
    }
}

/** Başlıklı yatay afiş sırası; sağ üstteki oklarla kaydırılır. */
@Composable
fun PosterRow(
    title: String,
    items: List<SearchResponse>,
    caption: String? = null,
    /** Eklentinin afiş vermediği yapımlar için yedek afiş adresleri (adrese göre); başlıksız istek atılır. */
    posterFallbacks: Map<String, String> = emptyMap(),
    /** Verilirse kartlarda oynat düğmesi çıkar; [busyUrl] ayrıntısı yüklenen kartın adresidir. */
    onPlay: ((SearchResponse) -> Unit)? = null,
    busyUrl: String? = null,
    onOpen: (SearchResponse) -> Unit,
) {
    val state = rememberLazyListState()
    RowSection(title, caption, state) {
        // Eklenti verisinde aynı adres tekrar edebiliyor; çakışan anahtar Compose'u çökertir
        itemsIndexed(items, key = { index, item -> "$index|${item.url}" }) { _, item ->
            val own = item.posterUrl?.takeIf { it.isNotBlank() }
            PosterCard(
                title = item.name,
                posterUrl = own ?: posterFallbacks[item.url],
                subtitle = typeLabel(item.type?.name),
                // Eklentiye ait başlıklar (Referer, çerez) üçüncü bir siteye gitmesin
                posterHeaders = if (own != null) item.posterHeaders else null,
                onPlay = onPlay?.let { play -> { play(item) } },
                playBusy = busyUrl == item.url,
                onClick = { onOpen(item) },
            )
        }
    }
}

/** Yatay sıra iskeleti: başlık, açıklama, kaydırma okları ve içerik. */
@Composable
fun RowSection(
    title: String,
    caption: String?,
    state: LazyListState,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val t = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 520.dp.toPx() }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) {
                Spacer(Modifier.width(10.dp))
                Text(caption, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
            }
            Spacer(Modifier.weight(1f))
            if (state.canScrollBackward || state.canScrollForward) {
                QuietIconButton(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft, t.scrollBack, size = 32.dp,
                    enabled = state.canScrollBackward,
                ) { scope.launch { state.animateScrollBy(-step) } }
                QuietIconButton(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, t.scrollForward, size = 32.dp,
                    enabled = state.canScrollForward,
                ) { scope.launch { state.animateScrollBy(step) } }
            }
        }
        LazyRow(
            state = state,
            // Dikey boşluk: üzerine gelince büyüyen afişin gölgesi sıranın sınırında kesilmesin
            contentPadding = PaddingValues(horizontal = PageGutter, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** Ekran başlığı: solda ad ve sayaç, sağda eylemler. */
@Composable
fun ScreenHeader(title: String, detail: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 36.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (detail != null) {
            Spacer(Modifier.width(14.dp))
            Text(detail, style = MaterialTheme.typography.labelMedium, color = SeyirColors.textMuted)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

enum class ButtonKind { Primary, Secondary, Quiet }

/** Uygulamanın düğmesi: birincil (kehribar), ikincil (dolgulu), sessiz (yalnız metin). */
@Composable
fun SeyirButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    large: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    // Devre dışı birincil düğme soluk kehribar yerine nötr yüzeye döner
    val kind = if (!enabled && kind == ButtonKind.Primary) ButtonKind.Secondary else kind
    val target = when (kind) {
        ButtonKind.Primary -> when {
            pressed -> SeyirColors.accentPressed
            hovered -> Color(0xFFEBB14C)
            else -> SeyirColors.accent
        }
        ButtonKind.Secondary -> when {
            pressed -> SeyirColors.surface
            hovered -> SeyirColors.surfaceHover
            else -> SeyirColors.surfaceHigh
        }
        ButtonKind.Quiet -> if (hovered) SeyirColors.surfaceHigh else Color.Transparent
    }
    val background by animateColorAsState(target, tween(140))
    val content = when (kind) {
        ButtonKind.Primary -> SeyirColors.onAccent
        ButtonKind.Secondary -> SeyirColors.text
        ButtonKind.Quiet -> if (hovered) SeyirColors.text else SeyirColors.textSecondary
    }
    Row(
        modifier
            .height(if (large) 48.dp else 40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                if (kind == ButtonKind.Primary && large) {
                    Modifier.shadow(14.dp, shape, ambientColor = SeyirColors.accent, spotColor = SeyirColors.accent)
                } else Modifier
            )
            .clip(shape)
            .background(background)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(1.dp, SeyirColors.hairline, shape) else Modifier)
            .focusRing(interaction, shape)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (large) 22.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(if (large) 22.dp else 18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = content,
            style = if (large) MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize)
            else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Yuvarlak, arka plansız simge düğmesi; üzerine gelince yüzey rengi alır. */
@Composable
fun QuietIconButton(
    icon: ImageVector,
    description: String,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    tint: Color = SeyirColors.textSecondary,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            hovered && enabled -> SeyirColors.surfaceHover
            filled -> SeyirColors.background.copy(alpha = 0.72f)
            else -> Color.Transparent
        },
        tween(140),
    )
    Box(
        Modifier.size(size)
            .clip(CircleShape)
            .background(background)
            .focusRing(interaction, CircleShape)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            // Yalnız simge olduğu için düğmenin adı burada verilir
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            tint = if (!enabled) SeyirColors.outline else if (hovered) SeyirColors.text else tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Tek satırlık metin kutusu (arama, süzgeç, ayar alanları). */
@Composable
fun SeyirTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    large: Boolean = false,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    /** Parola gibi gizli değerler için: yazılanlar nokta olarak gösterilir. */
    secret: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(if (large) 14.dp else 10.dp)
    val borderColor by animateColorAsState(
        when {
            focused -> SeyirColors.accent.copy(alpha = 0.85f)
            hovered -> SeyirColors.outline
            else -> SeyirColors.hairline
        },
        tween(140),
    )
    val textStyle = (if (large) MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize)
    else MaterialTheme.typography.bodyMedium).copy(color = SeyirColors.text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(SeyirColors.accent),
        interactionSource = interaction,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onAny = { onSubmit?.invoke() }),
        modifier = modifier
            .hoverable(interaction)
            // Alanın adı yer tutucudan alınır; böylece ekran okuyucu boş alanı da tanıtır
            .then(if (placeholder != null) Modifier.semantics { contentDescription = placeholder } else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent {
                if (onSubmit != null && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onSubmit()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier.height(if (large) 56.dp else 42.dp)
                    .clip(shape)
                    .background(SeyirColors.surface)
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = if (large) 18.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon, null,
                        tint = if (focused) SeyirColors.textSecondary else SeyirColors.textMuted,
                        modifier = Modifier.size(if (large) 22.dp else 18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle, color = SeyirColors.textMuted, maxLines = 1)
                    }
                    inner()
                }
                if (value.isNotEmpty() && large) {
                    QuietIconButton(Icons.Rounded.Close, LocalStrings.current.clear, size = 32.dp) { onValueChange("") }
                }
            }
        },
    )
}

/** Satır içi metin bağlantısı: sessiz renkte, üzerine gelince parlar. */
@Composable
fun TextLink(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp))
            .focusRing(interaction, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (hovered) SeyirColors.text else SeyirColors.textSecondary,
        textDecoration = if (hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null,
    )
}

/** "Dizi · 2019 · ★ 7,9" gibi künye satırı; boş parçalar atlanır. */
@Composable
fun MetaLine(parts: List<String?>, modifier: Modifier = Modifier, color: Color = SeyirColors.textSecondary) {
    val visible = parts.filterNot { it.isNullOrBlank() }
    if (visible.isEmpty()) return
    Text(
        visible.joinToString("  ·  "),
        modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Küçük etiket (tür, sürüm, kalite). */
@Composable
fun Chip(text: String, highlighted: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) SeyirColors.accent.copy(alpha = 0.16f) else SeyirColors.surfaceHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) SeyirColors.accent else SeyirColors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Durum noktası ve etiketi (Çalışıyor / Hata). */
@Composable
fun StatusDot(ok: Boolean, label: String = LocalStrings.current.let { if (ok) it.statusWorking else it.statusError }) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) SeyirColors.success else SeyirColors.danger))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (ok) SeyirColors.success else SeyirColors.danger)
    }
}

/** Liste için temalı dikey kaydırma çubuğu; kaydırılan içeriğin bulunduğu kutunun sağına oturur. */
@Composable
fun BoxScope.ScrollBar(state: LazyListState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
}

@Composable
fun BoxScope.ScrollBar(state: LazyGridState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
}

@Composable
fun BoxScope.ScrollBar(state: ScrollState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
}

/** Sitelerin başlığa eklediği yılı atar: "El Camino (2019)" → "El Camino"; yıl künyede zaten yazar. */
fun displayTitle(name: String): String = name.replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").ifBlank { name }

/**
 * Bazı siteler özeti iki dilde, araya "-----" koyarak veriyor; arayüz Türkçeyken Türkçe olan parça,
 * İngilizceyken Türkçe harf içermeyen parça seçilir (yoksa ilki).
 */
fun cleanPlot(plot: String?): String? {
    val parts = plot?.split(Regex("-{4,}"))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val turkish = I18n.current.locale.language == "tr"
    return parts.firstOrNull { part -> part.any { it in "çğıöşüÇĞİÖŞÜ" } == turkish } ?: parts.firstOrNull()
}

/** TvType adlarının arayüz dilindeki karşılıkları. */
fun typeLabel(type: String?): String? {
    val t = I18n.current
    return when (type) {
        "Movie" -> t.typeMovie
        "TvSeries" -> t.typeSeries
        "Anime", "AnimeMovie", "OVA" -> t.typeAnime
        "Cartoon" -> t.typeCartoon
        "Documentary" -> t.typeDocumentary
        "AsianDrama" -> t.typeAsianDrama
        "Live" -> t.typeLive
        "Torrent" -> t.typeTorrent
        null -> null
        else -> type
    }
}

@Composable
fun CenteredLoading(text: String? = null) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), color = SeyirColors.accent, strokeWidth = 2.5.dp, trackColor = SeyirColors.surfaceHigh)
        if (text != null) {
            Spacer(Modifier.height(18.dp))
            Text(text, color = SeyirColors.textMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Ortalanmış durum bildirimi: simge dairesi, başlık, açıklama ve isteğe bağlı eylem. */
@Composable
fun StatePanel(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    iconTint: Color = SeyirColors.textMuted,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(SeyirColors.surface).border(1.dp, SeyirColors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                Modifier.widthIn(max = 460.dp),
                color = SeyirColors.textMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}

@Composable
fun ErrorPanel(message: String, onRetry: (() -> Unit)? = null) {
    val lines = message.trim().lines()
    StatePanel(
        Icons.Rounded.ErrorOutline,
        lines.first(),
        lines.drop(1).joinToString("\n").ifBlank { null },
        iconTint = SeyirColors.danger,
        action = onRetry?.let { { SeyirButton(LocalStrings.current.retry, it) } },
    )
}

@Composable
fun EmptyPanel(title: String, hint: String? = null, icon: ImageVector = Icons.Rounded.Movie) {
    StatePanel(icon, title, hint)
}

/** Metin sekmeleri: etkin sekmenin altında kehribar çizgi, sayfa kenarlarına kadar kıl çizgi. */
@Composable
fun TextTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier.fillMaxWidth().drawBehind {
            val inset = PageGutter.toPx()
            drawLine(SeyirColors.hairline, Offset(inset, size.height - 0.5f), Offset(size.width - inset, size.height - 0.5f), 1f)
        },
        contentPadding = PaddingValues(horizontal = PageGutter - 12.dp),
    ) {
        items(labels.size) { index ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val active = index == selected
            Column(
                Modifier.clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .focusRing(interaction, RoundedCornerShape(8.dp))
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = active }
                    .padding(horizontal = 12.dp)
                    .width(androidx.compose.foundation.layout.IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    labels[index],
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        active -> SeyirColors.text
                        hovered -> SeyirColors.textSecondary
                        else -> SeyirColors.textMuted
                    },
                )
                Box(
                    Modifier.height(2.dp).fillMaxWidth()
                        .background(if (active) SeyirColors.accent else Color.Transparent, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
