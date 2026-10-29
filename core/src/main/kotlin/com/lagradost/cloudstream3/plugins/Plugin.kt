package com.lagradost.cloudstream3.plugins

import android.content.Context

/**
 * CloudStream uygulamasındaki Plugin sınıfının masaüstü karşılığı. Eklentiler bundan türer ve
 * `load(context)` içinde `registerMainAPI` / `registerExtractorAPI` çağırır.
 */
abstract class Plugin : BasePlugin() {
    /** Eklenti yüklenirken çağrılır. */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }
}
