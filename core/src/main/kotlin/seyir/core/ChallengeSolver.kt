package seyir.core

/** Bir tarayıcı sınamasının (Cloudflare vb.) çözümünden elde edilen çerezler ve user-agent. */
data class ChallengeResult(
    val cookies: Map<String, String>,
    val userAgent: String?,
)

/**
 * Tarayıcı sınamasını çözen bileşen. Android'de bu iş WebView ile yapılır; masaüstünde
 * uygulama bir uygulama (ör. gizli Chromium) atar. Atanmamışsa sınamalar çözülmez.
 */
fun interface ChallengeSolver {
    /** [url] adresini açıp sınamayı çözer; başarısızsa null döner. Çağıran iş parçacığını bloklar. */
    fun solve(url: String): ChallengeResult?
}

/**
 * Bu başlığı taşıyan isteklerde Cloudflare yakalayıcısı sınama çözücüyü (tarayıcıyı) hiç başlatmaz;
 * başlık yakalayıcıda silinir, sunucuya gitmez. Arka plan denetimleri (alan adı taraması gibi)
 * kullanıcıya tarayıcı penceresi açmasın ve çözücünün sırasında beklemesin diye kullanılır.
 */
internal const val NO_SOLVE_HEADER = "X-Seyir-No-Solve"
