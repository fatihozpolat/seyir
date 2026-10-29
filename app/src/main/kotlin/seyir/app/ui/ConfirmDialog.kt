package seyir.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import seyir.app.i18n.LocalStrings

/**
 * Uygulama içi onay penceresi (eklenti seçim penceresiyle aynı görünüm). Onay düğmesi kehribar,
 * vazgeç düğmesi sessizdir; pencere dışına tıklamak ya da Esc vazgeçmektir.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalStrings.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.width(460.dp)
                .shadow(32.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(SeyirColors.surface)
                .border(1.dp, SeyirColors.hairline, shape)
                .padding(24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = SeyirColors.textSecondary)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SeyirButton(t.cancel, onDismiss, kind = ButtonKind.Quiet)
                Spacer(Modifier.width(10.dp))
                SeyirButton(confirmLabel, onConfirm, kind = ButtonKind.Primary)
            }
        }
    }
}
