package seyir.app

import seyir.app.PlaybackFlow.Decision
import seyir.core.player.PlaybackSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFlowTest {
    private fun summary(
        endReason: String?,
        position: Double? = 100.0,
        started: Boolean = true,
        userStopped: Boolean = false,
        error: String? = null,
    ) = PlaybackSummary(
        position = position,
        duration = 2700.0,
        reachedEnd = false,
        error = error,
        started = started,
        userStopped = userStopped,
        endReason = endReason,
    )

    @Test
    fun `attempt order starts at the chosen link then follows the dialog order and wraps`() {
        val shown = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "b", "c", "d"), PlaybackFlow.attemptOrder(shown, "a") { it })
        assertEquals(listOf("c", "d", "a", "b"), PlaybackFlow.attemptOrder(shown, "c") { it })
        assertEquals(listOf("d", "a", "b", "c"), PlaybackFlow.attemptOrder(shown, "d") { it })
    }

    @Test
    fun `chosen link outside the list is tried alone`() {
        assertEquals(listOf("z"), PlaybackFlow.attemptOrder(listOf("a", "b"), "z") { it })
        assertEquals(listOf("z"), PlaybackFlow.attemptOrder(emptyList(), "z") { it })
    }

    @Test
    fun `links are matched by key not identity`() {
        data class L(val url: String, val n: Int)
        val shown = listOf(L("u1", 1), L("u2", 2), L("u3", 3))
        val order = PlaybackFlow.attemptOrder(shown, L("u2", 99)) { it.url }
        assertEquals(listOf("u2", "u3", "u1"), order.map { it.url })
    }

    @Test
    fun `failed start moves to the next source then gives up`() {
        val failed = summary("error", position = null, started = false, error = "mpv 2 koduyla kapandı")
        assertEquals(Decision.TrySource(1), PlaybackFlow.decide(failed, attempt = 0, total = 3, autoplayNext = true))
        assertEquals(Decision.TrySource(2), PlaybackFlow.decide(failed, attempt = 1, total = 3, autoplayNext = true))
        assertEquals(Decision.Exhausted, PlaybackFlow.decide(failed, attempt = 2, total = 3, autoplayNext = true))
        assertEquals(Decision.Exhausted, PlaybackFlow.decide(failed, attempt = 0, total = 1, autoplayNext = false))
    }

    @Test
    fun `natural end plays next only when the setting is on`() {
        val ended = summary("eof", position = 2700.0)
        assertEquals(Decision.PlayNext, PlaybackFlow.decide(ended, 0, 1, autoplayNext = true))
        assertEquals(Decision.Done, PlaybackFlow.decide(ended, 0, 1, autoplayNext = false))
    }

    @Test
    fun `user quit and app stop do nothing`() {
        assertEquals(Decision.Done, PlaybackFlow.decide(summary("quit"), 0, 3, true))
        assertEquals(Decision.Done, PlaybackFlow.decide(summary("eof", userStopped = true), 0, 3, true))
        assertEquals(Decision.Done, PlaybackFlow.decide(summary("error", userStopped = true, position = null, started = false), 0, 3, true))
    }

    @Test
    fun `stream dropping mid playback is neither fallback nor next episode`() {
        val dropped = summary("error", position = 900.0, error = "mpv 2 koduyla kapandı")
        assertEquals(Decision.Done, PlaybackFlow.decide(dropped, 0, 3, true))
    }

    @Test
    fun `instant eof counts as a failed source`() {
        val instant = summary("eof", position = 0.2)
        assertEquals(Decision.TrySource(1), PlaybackFlow.decide(instant, 0, 2, true))
    }
}
