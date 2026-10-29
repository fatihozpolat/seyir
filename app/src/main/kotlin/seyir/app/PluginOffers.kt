package seyir.app

import seyir.app.data.JsonStore
import seyir.core.AppDirs
import java.io.File

/**
 * Depolardaki eklentilerden hangilerinin kullanıcıya zaten "sunulduğunu" (`plugin-offers.json`) hatırlar; açılışta
 * depoya sonradan eklenen eklentiler kendiliğinden kurulurken iki şey korunur:
 * - [removed]: kullanıcının kaldırdığı eklentiler geri kurulmaz (elle yeniden kurunca listeden çıkar).
 * - [seen]: depo başına, kullanıcının seçim penceresinde gördüğü (kurduğu ya da bilerek seçmediği) eklentiler.
 *   Depo için kayıt yoksa (özellikten önce eklenmiş depo) kurulu olmayan her eklenti yeni sayılır.
 */
class PluginOffers(private val file: File = File(AppDirs.data, "plugin-offers.json")) {
    data class State(val removed: Set<String> = emptySet(), val seen: Map<String, Set<String>> = emptyMap())

    private var state: State = JsonStore.read<State>(file) { State() }

    @Synchronized
    fun isRemoved(internalName: String): Boolean = internalName in state.removed

    @Synchronized
    fun seen(repository: String): Set<String>? = state.seen[PluginSources.normalize(repository)]

    @Synchronized
    fun markRemoved(internalName: String) = save(state.copy(removed = state.removed + internalName))

    @Synchronized
    fun clearRemoved(internalName: String) {
        if (internalName in state.removed) save(state.copy(removed = state.removed - internalName))
    }

    @Synchronized
    fun markSeen(repository: String, names: Collection<String>) {
        val key = PluginSources.normalize(repository)
        val merged = state.seen[key].orEmpty() + names
        if (merged != state.seen[key]) save(state.copy(seen = state.seen + (key to merged)))
    }

    /**
     * Depodaki [listed] eklentilerden kendiliğinden kurulacak olanlar: kurulu değil, kullanıcı kaldırmamış ve
     * kullanıcıya daha önce sunulmamış.
     */
    @Synchronized
    fun newOnes(repository: String, listed: Collection<String>, installed: Set<String>): List<String> {
        val known = seen(repository)
        return listed.filter { it !in installed && it !in state.removed && (known == null || it !in known) }
    }

    private fun save(next: State) {
        state = next
        JsonStore.write(file, next)
    }
}
