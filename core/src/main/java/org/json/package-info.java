/**
 * Android'in org.json uygulaması (AOSP libcore/json), Android'e özel açıklamalar ayıklanarak.
 * org.json:json kitaplığı yerine bu kullanılır: Android'de getString() dizi ya da sayı değerini de
 * metne çeviriyor, org.json:json ise JSONException veriyor; eklentiler Android davranışına göre
 * yazıldığı için (ör. InatBox kanal başlıkları) aynı uygulama gerekiyor.
 */
package org.json;
