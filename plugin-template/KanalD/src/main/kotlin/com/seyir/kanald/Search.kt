package com.seyir.kanald

import java.util.Locale

private val TR: Locale = Locale.forLanguageTag("tr")

/**
 * Kanal sitelerinin yerel kataloğunda ad eşleştirmesi: Türkçe küçük harf, Türkçe harfler sadeleştirilir
 * ("muge anli" → "Müge Anlı") ve harf/rakam dışındakiler yok sayılır ("tv8" → "TV 8").
 */
fun titleMatches(title: String, query: String): Boolean {
    fun fold(text: String) = text.lowercase(TR)
        .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u')
        .replace('â', 'a').replace('î', 'i').replace('û', 'u')
        .filter { it.isLetterOrDigit() }
    val q = fold(query)
    return q.isNotEmpty() && fold(title).contains(q)
}
