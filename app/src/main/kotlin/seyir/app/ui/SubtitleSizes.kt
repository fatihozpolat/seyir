package seyir.app.ui

import seyir.app.i18n.UiStrings

/** Altyazı boyutu seçenekleri (mpv `sub-scale` çarpanı ve adı); ayarlar ekranı ve oynatıcı ortak kullanır. */
fun subtitleSizes(t: UiStrings): List<Pair<Double, String>> = listOf(
    0.8 to t.subtitleSizeSmall,
    1.0 to t.subtitleSizeNormal,
    1.25 to t.subtitleSizeLarge,
    1.5 to t.subtitleSizeHuge,
)
