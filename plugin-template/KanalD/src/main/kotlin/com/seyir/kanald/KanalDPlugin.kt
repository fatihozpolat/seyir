package com.seyir.kanald

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/** Eklentinin giriş noktası: Seyir eklentiyi yüklerken bu sınıfı oluşturup load() çağırır. */
@CloudstreamPlugin
class KanalDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KanalD())
    }
}
