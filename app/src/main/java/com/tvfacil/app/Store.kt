package com.tvfacil.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Uma lista de canais comprada (link M3U ou servidor + usuário + senha). */
data class IptvList(
    val id: String,
    val name: String,
    val type: String, // "m3u" ou "xtream"
    val url: String,
    val server: String,
    val user: String,
    val pass: String,
    val hls: Boolean
)

/** Canal "favorito": o número que os pais digitam no controle. */
data class Fav(val name: String, val number: Int)

/** Tudo que o app guarda no aparelho (listas, números escolhidos, último canal). */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tvfacil", Context.MODE_PRIVATE)

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    // ---------- listas ----------

    fun getLists(ctx: Context): List<IptvList> {
        val arr = try {
            JSONArray(prefs(ctx).getString("lists", "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val out = ArrayList<IptvList>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                IptvList(
                    o.optString("id"),
                    o.optString("name"),
                    o.optString("type", "m3u"),
                    o.optString("url"),
                    o.optString("server"),
                    o.optString("user"),
                    o.optString("pass"),
                    o.optBoolean("hls")
                )
            )
        }
        return out
    }

    private fun saveLists(ctx: Context, lists: List<IptvList>) {
        val arr = JSONArray()
        for (l in lists) {
            arr.put(
                JSONObject()
                    .put("id", l.id)
                    .put("name", l.name)
                    .put("type", l.type)
                    .put("url", l.url)
                    .put("server", l.server)
                    .put("user", l.user)
                    .put("pass", l.pass)
                    .put("hls", l.hls)
            )
        }
        prefs(ctx).edit().putString("lists", arr.toString()).apply()
    }

    fun addList(ctx: Context, l: IptvList) {
        saveLists(ctx, getLists(ctx) + l)
        if (prefs(ctx).getString("active", null) == null) setActive(ctx, l.id)
    }

    fun removeList(ctx: Context, id: String) {
        saveLists(ctx, getLists(ctx).filter { it.id != id })
        Playlist.deleteCache(ctx, id)
    }

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString("active", id).apply()
    }

    /** Id da lista em uso (se a marcada não existir mais, usa a primeira). */
    fun activeId(ctx: Context): String? {
        val lists = getLists(ctx)
        val a = prefs(ctx).getString("active", null)
        return if (lists.any { it.id == a }) a else lists.firstOrNull()?.id
    }

    fun getActive(ctx: Context): IptvList? {
        val id = activeId(ctx) ?: return null
        return getLists(ctx).firstOrNull { it.id == id }
    }

    // ---------- números escolhidos ("Meus canais") ----------

    fun getFavs(ctx: Context): List<Fav> {
        val arr = try {
            JSONArray(prefs(ctx).getString("favs", "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val out = ArrayList<Fav>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optInt("number", 0)
            if (n > 0) out.add(Fav(o.optString("name"), n))
        }
        return out
    }

    fun setFavs(ctx: Context, favs: List<Fav>) {
        val arr = JSONArray()
        for (f in favs) arr.put(JSONObject().put("name", f.name).put("number", f.number))
        prefs(ctx).edit().putString("favs", arr.toString()).apply()
    }

    // ---------- último canal / canal anterior ----------

    fun getLast(ctx: Context): Int = prefs(ctx).getInt("last", 0)
    fun setLast(ctx: Context, n: Int) = prefs(ctx).edit().putInt("last", n).apply()
    fun getPrev(ctx: Context): Int = prefs(ctx).getInt("prev", 0)
    fun setPrev(ctx: Context, n: Int) = prefs(ctx).edit().putInt("prev", n).apply()
}
