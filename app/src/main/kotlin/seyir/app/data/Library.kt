package seyir.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import seyir.core.AppDirs
import java.io.File

/** İzleme geçmişindeki tek bir öğe (film veya bölüm) ve kaldığı yer. */
data class WatchEntry(
    val provider: String,
    /** Detay sayfasının adresi (load'a verilen). */
    val loadUrl: String,
    val showName: String,
    val posterUrl: String? = null,
    val itemTitle: String,
    /** loadLinks'e verilen veri. */
    val itemData: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Double = 0.0,
    val duration: Double? = null,
    val finished: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    @get:JsonIgnore
    val key: String get() = keyOf(provider, itemData)

    /** 0..1 arası ilerleme; süre bilinmiyorsa null. */
    @get:JsonIgnore
    val progress: Float? get() = duration?.takeIf { it > 0 }?.let { (position / it).toFloat().coerceIn(0f, 1f) }

    companion object {
        fun keyOf(provider: String, itemData: String) = "$provider|$itemData"
    }
}

/** Listeme eklenmiş bir yapım. */
data class Bookmark(
    val provider: String,
    val url: String,
    val name: String,
    val posterUrl: String? = null,
    val type: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

internal data class LibraryFile(
    val history: List<WatchEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)

/** İzleme geçmişi ve listem; `library.json` dosyasında saklanır. */
class LibraryStore(private val file: File = File(AppDirs.data, "library.json")) {
    private var data: LibraryFile = JsonStore.read(file) { LibraryFile() }.let { it.copy(history = capped(it.history)) }

    // mpv dinleyicisi her ~10 sn'de kayıt düşer; yazımlar birleştirilip arka planda yapılır
    private val writer = DebouncedWriter(file) { synchronized(this) { data } }

    // Sıralı görünümler her çağrıda yeniden hesaplanmasın diye değişene dek saklanır
    private var historyView: List<WatchEntry>? = null
    private var bookmarksView: List<Bookmark>? = null

    val history: List<WatchEntry>
        @Synchronized get() = historyView ?: data.history.sortedByDescending { it.updatedAt }.also { historyView = it }
    val bookmarks: List<Bookmark>
        @Synchronized get() = bookmarksView ?: data.bookmarks.sortedByDescending { it.addedAt }.also { bookmarksView = it }

    @Synchronized
    fun entry(provider: String, itemData: String): WatchEntry? =
        data.history.firstOrNull { it.key == WatchEntry.keyOf(provider, itemData) }

    /** Bir yapımın bölümlerine ait kayıtlar (detay ekranındaki ilerleme çubukları için). */
    @Synchronized
    fun entriesFor(provider: String, loadUrl: String): Map<String, WatchEntry> =
        data.history.filter { it.provider == provider && it.loadUrl == loadUrl }.associateBy { it.itemData }

    @Synchronized
    fun record(entry: WatchEntry) {
        update { it.copy(history = capped(it.history.filterNot { old -> old.key == entry.key } + entry)) }
    }

    @Synchronized
    fun removeHistory(key: String) {
        update { it.copy(history = it.history.filterNot { old -> old.key == key }) }
    }

    @Synchronized
    fun isBookmarked(provider: String, url: String) = data.bookmarks.any { it.provider == provider && it.url == url }

    @Synchronized
    fun toggleBookmark(bookmark: Bookmark): Boolean {
        val exists = isBookmarked(bookmark.provider, bookmark.url)
        val rest = data.bookmarks.filterNot { it.provider == bookmark.provider && it.url == bookmark.url }
        update { it.copy(bookmarks = if (exists) rest else rest + bookmark) }
        return !exists
    }

    /** Bekleyen yazımı hemen diske işler (kapanışta ve testlerde). */
    fun flush() = writer.flush()

    private fun update(transform: (LibraryFile) -> LibraryFile) {
        data = transform(data)
        historyView = null
        bookmarksView = null
        writer.schedule()
    }

    /** Geçmiş sınırsız büyümesin diye en yeni [MAX_HISTORY] kayıt tutulur. */
    private fun capped(history: List<WatchEntry>): List<WatchEntry> =
        if (history.size <= MAX_HISTORY) history else history.sortedBy { it.updatedAt }.takeLast(MAX_HISTORY)

    companion object {
        const val MAX_HISTORY = 500
    }
}
