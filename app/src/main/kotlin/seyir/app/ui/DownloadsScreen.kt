package seyir.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import seyir.app.AppController
import seyir.app.DownloadItem
import seyir.app.DownloadService
import seyir.app.DownloadStatus
import seyir.app.i18n.LocalStrings
import java.awt.Desktop

@Composable
fun DownloadsScreen(controller: AppController) {
    val t = LocalStrings.current
    val service = controller.downloads
    val running = service.items.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t.navDownloads, if (running > 0) t.downloadsRunning(running) else null) {
            SeyirButton(t.openFolder, { runCatching { Desktop.getDesktop().open(service.directory) } }, icon = Icons.Rounded.FolderOpen)
        }
        if (service.items.isEmpty()) {
            EmptyPanel(
                t.noDownloads,
                t.noDownloadsHint(service.directory.toString()),
                Icons.Rounded.Download,
            )
            return
        }
        Box(Modifier.fillMaxSize()) {
            val state = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(service.items, key = { it.target.absolutePath }) { item -> DownloadRow(service, item) }
            }
            ScrollBar(state)
        }
    }
}

@Composable
private fun DownloadRow(service: DownloadService, item: DownloadItem) {
    val t = LocalStrings.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.widthIn(max = 960.dp).fillMaxWidth().clip(shape).background(SeyirColors.surface)
            .border(1.dp, SeyirColors.hairline, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = when (item.status) {
            DownloadStatus.RUNNING -> Icons.Rounded.Download to SeyirColors.accent
            DownloadStatus.QUEUED -> Icons.Rounded.Download to SeyirColors.textMuted
            DownloadStatus.DONE -> Icons.Rounded.CheckCircle to SeyirColors.success
            DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline to SeyirColors.danger
            DownloadStatus.CANCELLED -> Icons.Rounded.Close to SeyirColors.textMuted
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(SeyirColors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.status == DownloadStatus.RUNNING) {
                Spacer(Modifier.height(8.dp))
                val p = item.progress
                if (p != null) {
                    ProgressBar(p)
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = SeyirColors.accent,
                        trackColor = SeyirColors.surfaceHover,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.detail,
                style = MaterialTheme.typography.labelMedium,
                color = if (item.status == DownloadStatus.FAILED) SeyirColors.danger else SeyirColors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> SeyirButton(t.cancel, { service.cancel(item) }, kind = ButtonKind.Quiet, icon = Icons.Rounded.Close)
            DownloadStatus.DONE -> SeyirButton(
                t.open,
                { runCatching { Desktop.getDesktop().open(item.target) } },
                icon = Icons.Rounded.PlayArrow,
            )
            else -> Unit
        }
    }
}
