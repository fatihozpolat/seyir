package seyir.app

import seyir.core.player.PlaybackSummary

/**
 * Oynatma akışının saf karar mantığı (mpv'ye, arayüze ve ağa dokunmaz; bu yüzden birim sınanır).
 * [PlaybackService] yalnızca bu kararları uygular.
 */
object PlaybackFlow {
    /** Bir oturum bittiğinde yapılacak iş. */
    sealed interface Decision {
        /** Yedek kaynak: zincirdeki [index]'inci bağlantıyı dene. */
        data class TrySource(val index: Int) : Decision

        /** Bütün kaynaklar denendi ve hiçbiri açılmadı. */
        data object Exhausted : Decision

        /** Bölüm sonuna kadar izlendi; ayar açıksa sıradaki bölüm başlar. */
        data object PlayNext : Decision

        /** Yapılacak iş yok (kullanıcı kapattı ya da durdurdu, akış ortada koptu...). */
        data object Done : Decision
    }

    /**
     * Kullanıcının seçtiği bağlantıdan başlayan deneme sırası: seçilen, ardından pencerede ondan sonra
     * gelenler (pencere sırasıyla), en sonda seçilenden önce gelenler. [shown] yoksa ya da seçileni
     * içermiyorsa yalnız seçilen denenir. Eşleşme bağlantının [key] değerine (adres) göredir.
     */
    fun <T> attemptOrder(shown: List<T>, chosen: T, key: (T) -> String): List<T> {
        val at = shown.indexOfFirst { key(it) == key(chosen) }
        if (at < 0) return listOf(chosen)
        return listOf(shown[at]) + shown.drop(at + 1) + shown.take(at)
    }

    /**
     * @param attempt şu an oynatılan bağlantının zincirdeki sırası
     * @param total zincirdeki bağlantı sayısı
     * @param autoplayNext "Sıradaki bölümü kendiliğinden aç" ayarı
     */
    fun decide(summary: PlaybackSummary, attempt: Int, total: Int, autoplayNext: Boolean): Decision = when {
        summary.startFailed -> if (attempt + 1 < total) Decision.TrySource(attempt + 1) else Decision.Exhausted
        summary.naturalEnd && autoplayNext -> Decision.PlayNext
        else -> Decision.Done
    }
}
