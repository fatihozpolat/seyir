package seyir.app.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import seyir.core.player.MpvCommand
import seyir.core.player.PlayerState
import seyir.core.player.ThumbnailGenerator
import java.util.LinkedHashMap

/**
 * Oynayan dosyanın ileri sarma önizlemeleri. Üretici ([ThumbnailGenerator]) ilk istekte, videonun en-boy oranı
 * ve en düşük kalite katmanı bilinince kurulur; kareler [BUCKET_S] saniyelik dilimlere göre önbelleğe alınır.
 */
class ThumbnailSource(private val command: MpvCommand, private val url: String) : AutoCloseable {
    private var generator: ThumbnailGenerator? = null
    private val cache = object : LinkedHashMap<Long, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > CACHE_SIZE
    }

    @Volatile
    private var closed = false

    /** Önbellekteki kare; yoksa null (istek için [request]). */
    fun cached(seconds: Double): ImageBitmap? = synchronized(cache) { cache[bucket(seconds)] }

    /** [seconds] konumunun karesini üretir; hazır olunca [onReady] üretim iş parçacığından çağrılır. */
    fun request(seconds: Double, state: PlayerState, onReady: (ImageBitmap) -> Unit) {
        if (closed) return
        cached(seconds)?.let { return onReady(it) }
        val gen = synchronized(this) {
            // Kapanışla yarışan istek yeni (hiç kapanmayacak) bir süreç açmasın
            if (closed) return
            generator ?: run {
                val width = ThumbnailGenerator.WIDTH
                val height = ThumbnailGenerator.heightFor(state.videoWidth ?: 0, state.videoHeight ?: 0)
                // En düşük katman yeter; sürüm bilgisi yoksa yayının varsayılanı açılır
                ThumbnailGenerator(command, url, width, height, state.qualities.lastOrNull()?.editionId).also { generator = it }
            }
        }
        val key = bucket(seconds)
        gen.request(key * BUCKET_S) { frame ->
            if (frame == null || closed) return@request
            val bitmap = Image.makeRaster(
                ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                frame.bgra,
                frame.width * 4,
            ).toComposeImageBitmap()
            synchronized(cache) { cache[key] = bitmap }
            onReady(bitmap)
        }
    }

    /** Üreticiyi önceden açar (fare alt çubuğa gelince): ilk önizleme yayının açılmasını beklemesin. */
    fun warmUp(state: PlayerState) {
        if (generator == null && state.loaded && state.duration != null) request(state.position ?: 0.0, state) {}
    }

    private fun bucket(seconds: Double): Long = (seconds / BUCKET_S).toLong()

    override fun close() {
        synchronized(this) {
            closed = true
            generator?.close()
        }
        synchronized(cache) { cache.clear() }
    }

    private companion object {
        const val BUCKET_S = 2.0
        const val CACHE_SIZE = 240
    }
}
