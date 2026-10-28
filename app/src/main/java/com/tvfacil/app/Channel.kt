package com.tvfacil.app

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** Canal como veio da lista (sem número). */
data class RawChannel(val name: String, val url: String, val group: String)

/** Canal já numerado. [favorite] = o número foi escolhido por você em "Meus canais". */
data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val group: String,
    val favorite: Boolean
)

/** O "cardápio" de canais que o controle remoto enxerga. */
class Lineup(val all: List<Channel>) {
    val byNumber: Map<Int, Channel> = all.associateBy { it.number }
    val hasFavorites: Boolean = all.any { it.favorite }

    /** Canais percorridos pelas teclas CH+ / CH-: só "Meus canais", se houver; senão todos. */
    val zap: List<Channel> = if (hasFavorites) all.filter { it.favorite } else all

    /** Quantos dígitos tem o maior número; quando o usuário digita tudo, troca na hora. */
    val maxDigits: Int = (zap.maxOfOrNull { it.number } ?: 1).toString().length

    companion object {
        val EMPTY = Lineup(emptyList())
    }
}

object Channels {
    private val ACCENTS = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Minúsculas e sem acento, para a busca. */
    fun norm(s: String): String =
        ACCENTS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /**
     * Numeração:
     *  - canais com número escolhido em "Meus canais" ficam com esse número;
     *  - se não houver nenhum, todos recebem 1, 2, 3... na ordem da lista;
     *  - se houver, os demais começam em 1000 (ficam fora do caminho dos pais).
     */
    fun build(raw: List<RawChannel>, favs: List<Fav>): Lineup {
        val wanted = HashMap<String, Int>()
        for (f in favs) wanted[key(f.name)] = f.number

        val used = HashSet<Int>()
        val result = ArrayList<Channel>(raw.size)
        val rest = ArrayList<RawChannel>()

        for (r in raw) {
            val n = wanted[key(r.name)]
            if (n != null && n > 0 && used.add(n)) {
                result.add(Channel(n, r.name, r.url, r.group, true))
            } else {
                rest.add(r)
            }
        }

        var next = 1
        if (result.isNotEmpty()) {
            val max = result.maxOf { it.number }
            next = if (max < 1000) 1000 else (max / 1000 + 1) * 1000
        }
        for (r in rest) {
            while (next in used) next++
            used.add(next)
            result.add(Channel(next, r.name, r.url, r.group, false))
            next++
        }

        result.sortBy { it.number }
        return Lineup(result)
    }

    /** Lê a lista em uso (do cache do aparelho) e aplica os números escolhidos. */
    fun loadActive(ctx: Context): Lineup {
        val list = Store.getActive(ctx) ?: return Lineup.EMPTY
        val raw = Playlist.loadCached(ctx, list) ?: return Lineup.EMPTY
        return build(raw, Store.getFavs(ctx))
    }
}
