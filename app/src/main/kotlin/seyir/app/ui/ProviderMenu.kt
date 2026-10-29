package seyir.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import java.util.Locale
import seyir.app.i18n.LocalStrings

private val TR: Locale = Locale.forLanguageTag("tr")

/** Ad Türkçe küçük harfe göre ve harf/rakam dışı karakterler yok sayılarak eşleşir ("tv8" → "TV 8"). */
fun providerMatches(api: MainAPI, query: String): Boolean {
    if (query.isBlank()) return true
    fun norm(text: String) = text.lowercase(TR).filter { it.isLetterOrDigit() }
    return norm(api.name).contains(norm(query))
}

/**
 * Aranabilir sağlayıcı menüsü: açılınca üstteki kutuya odaklanır, yazdıkça liste süzülür,
 * Enter ilk eşleşeni seçer. [allLabel] verilirse en üstte "hepsi" seçeneği çıkar (çoklu seçimde).
 *
 * @param closeOnPick tek seçimde menü seçince kapanır; çoklu seçimde açık kalır
 */
@Composable
fun ProviderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    providers: List<MainAPI>,
    isSelected: (MainAPI) -> Boolean,
    onPick: (MainAPI) -> Unit,
    closeOnPick: Boolean,
    allLabel: String? = null,
    allSelected: Boolean = false,
    onPickAll: () -> Unit = {},
) {
    var query by remember(expanded) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shown = providers.filter { providerMatches(it, query) }

    fun pick(api: MainAPI) {
        onPick(api)
        if (closeOnPick) onDismiss()
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 560.dp).width(300.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = SeyirColors.surfaceHigh,
        border = BorderStroke(1.dp, SeyirColors.hairline),
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        SeyirTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth(),
            placeholder = LocalStrings.current.searchProviders,
            leadingIcon = Icons.Rounded.Search,
            focusRequester = focus,
            onSubmit = { shown.firstOrNull()?.let(::pick) },
        )
        if (allLabel != null && query.isBlank()) {
            DropdownMenuItem(
                text = { Text(allLabel, style = MaterialTheme.typography.labelLarge) },
                trailingIcon = if (allSelected) {
                    { Icon(Icons.Rounded.Check, null, tint = SeyirColors.accent, modifier = Modifier.size(18.dp)) }
                } else null,
                onClick = onPickAll,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = SeyirColors.hairline)
        }
        if (shown.isEmpty()) {
            Text(
                LocalStrings.current.noMatchingProvider,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = SeyirColors.textMuted,
            )
        }
        shown.forEach { api ->
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(api.name, style = MaterialTheme.typography.labelLarge)
                        Text(
                            api.supportedTypes.mapNotNull { typeLabel(it.name) }.distinct().joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = SeyirColors.textMuted,
                        )
                    }
                },
                trailingIcon = if (isSelected(api)) {
                    { Icon(Icons.Rounded.Check, null, tint = SeyirColors.accent, modifier = Modifier.size(18.dp)) }
                } else null,
                onClick = { pick(api) },
            )
        }
    }
}
