package seyir.app

import seyir.app.i18n.UiStrings
import seyir.core.FailureKind
import seyir.core.ProbeFailure
import seyir.core.ProbeResult
import seyir.core.ProbeStatus
import seyir.core.ProbeStepKind
import seyir.core.StepResult
import seyir.core.StepStatus

/** Sınama adımının arayüz dilindeki adı. */
fun probeStepLabel(t: UiStrings, step: ProbeStepKind): String = when (step) {
    ProbeStepKind.MAIN_PAGE -> t.probeStepMainPage
    ProbeStepKind.SEARCH -> t.probeStepSearch
    ProbeStepKind.LOAD -> t.probeStepLoad
    ProbeStepKind.LINKS -> t.probeStepLinks
    ProbeStepKind.LINK_CHECK -> t.probeStepLinkCheck
}

/** Başarısızlık ya da atlama nedeninin kullanıcıya gösterilen metni. */
fun probeFailureText(t: UiStrings, failure: ProbeFailure): String = when (failure.kind) {
    FailureKind.TIMEOUT -> t.probeFailTimeout
    // Ayrıntı ham (çoğunlukla İngilizce) istisna iletisidir; uygulamanın ortak eşlemesi tanırsa o cümle gösterilir
    FailureKind.NETWORK -> knownMessage(failure.detail, t)?.takeUnless { it == t.errorNoNetwork || it == t.errorTimeout } ?: t.probeFailNetwork
    FailureKind.EMPTY -> t.probeFailEmpty
    FailureKind.NO_PLAYABLE -> t.probeFailNoPlayable
    FailureKind.HTTP -> t.probeFailHttp(failure.detail.orEmpty())
    FailureKind.HTML -> t.probeFailHtml
    FailureKind.NOT_MEDIA -> t.probeFailNotMedia
    FailureKind.BAD_PLAYLIST -> t.probeFailBadPlaylist
    FailureKind.UNSUPPORTED -> t.probeFailUnsupported
    FailureKind.INCOMPATIBLE -> t.probeFailIncompatible(failure.detail.orEmpty().take(120))
    FailureKind.ERROR -> knownMessage(failure.detail, t) ?: t.probeFailError(failure.detail.orEmpty().take(120))
    FailureKind.PREVIOUS_FAILED -> t.probeSkippedPrevious
    FailureKind.UNCHECKABLE -> t.probeSkippedUncheckable
    FailureKind.CLOUDFLARE -> t.probeFailCloudflare
}

/** Genel durumun etiketi. */
fun probeStatusLabel(t: UiStrings, status: ProbeStatus): String = when (status) {
    ProbeStatus.GREEN -> t.probeStatusGreen
    ProbeStatus.YELLOW -> t.probeStatusPartial
    ProbeStatus.RED -> t.probeStatusRed
}

/** Sonucun rozet etiketi: Cloudflare doğrulaması bekleyen sağlayıcı sarıdır ama "kısmen çalışıyor" değil, "doğrulama gerekiyor" der. */
fun probeResultLabel(t: UiStrings, result: ProbeResult): String =
    if (result.needsChallenge) t.probeStatusChallenge else probeStatusLabel(t, result.status)

/** Adımın tek satırlık dökümü: "Arama · Tamam · başlık" ya da "Bağlantılar · Başarısız · Zaman aşımı". */
fun probeStepLine(t: UiStrings, step: StepResult): String {
    val state = when (step.status) {
        StepStatus.OK -> t.probeStepOk
        StepStatus.FAILED -> t.probeStepFailed
        StepStatus.SKIPPED -> t.probeStepSkipped
    }
    val detail = step.failure?.let { probeFailureText(t, it) } ?: step.info
    return listOfNotNull(probeStepLabel(t, step.step), state, detail).joinToString(" · ")
}

/** Sağlayıcı sağlık haritasına yazılacak kısa neden; kırmızı sonuçta "Adım: neden". */
fun ProbeResult.healthMessage(t: UiStrings): String? {
    val (step, failure) = firstFailure ?: return null
    return "${probeStepLabel(t, step)}: ${probeFailureText(t, failure)}"
}
