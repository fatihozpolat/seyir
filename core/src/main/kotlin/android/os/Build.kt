package android.os

/**
 * Android `Build` bilgileri. Eklentiler bunu çoğunlukla API düzeyine göre yol seçmek için okur;
 * masaüstünde yeni bir Android sürümü gibi davranılır.
 */
object Build {
    @JvmField val MANUFACTURER: String = "Seyir"
    @JvmField val MODEL: String = "Desktop"
    @JvmField val BRAND: String = "Seyir"
    @JvmField val DEVICE: String = "desktop"

    object VERSION {
        @JvmField val SDK_INT: Int = VERSION_CODES.UPSIDE_DOWN_CAKE
        @JvmField val RELEASE: String = "14"
    }

    object VERSION_CODES {
        const val LOLLIPOP: Int = 21
        const val M: Int = 23
        const val N: Int = 24
        const val O: Int = 26
        const val P: Int = 28
        const val Q: Int = 29
        const val R: Int = 30
        const val S: Int = 31
        const val TIRAMISU: Int = 33
        const val UPSIDE_DOWN_CAKE: Int = 34
    }
}
