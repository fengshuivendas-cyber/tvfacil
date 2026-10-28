package com.tvfacil.app

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Baixa e lê as listas de canais (M3U ou Xtream: servidor + usuário + senha).
 * Só entram canais ao vivo (filmes e séries são ignorados).
 * A lista baixada fica guardada no aparelho, então o app abre rápido e funciona
 * mesmo se o servidor da lista estiver fora do ar na hora.
 */
object Playlist {
    const val UA = "VLC/3.0.20 LibVLC/3.0.20"

    private val statuses = ConcurrentHashMap<String, String>()
    private val busy: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val memo = ConcurrentHashMap<String, Pair<String, List<RawChannel>>>()

    private val ATTR_RE = Regex("[A-Za-z0-9_-]+=\"[^\"]*\"")
    private val GROUP_RE = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val TVGNAME_RE = Regex("tvg-name=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    fun isBusy(id: String): Boolean = busy.contains(id)
    fun anyBusy(): Boolean = busy.isNotEmpty()

    fun cacheFile(ctx: Context, id: String) = File(ctx.filesDir, "list_$id.tsv")

    fun deleteCache(ctx: Context, id: String) {
        cacheFile(ctx, id).delete()
        memo.remove(id)
        statuses.remove(id)
    }

    fun lastError(id: String): String? = statuses[id]?.takeIf { it.startsWith("Erro") }

    // ---------- leitura do cache ----------

    fun loadCached(ctx: Context, l: IptvList): List<RawChannel>? {
        val f = cacheFile(ctx, l.id)
        if (!f.exists()) return null
        val stamp = "${f.lastModified()}:${f.length()}"
        val hit = memo[l.id]
        if (hit != null && hit.first == stamp) return hit.second

        val out = ArrayList<RawChannel>()
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size >= 2) out.add(RawChannel(p[0], p[1], if (p.size > 2) p[2] else ""))
            }
        }
        memo[l.id] = Pair(stamp, out)
        return out
    }

    /** Texto de situação mostrado na tela de configuração e no celular. */
    fun statusText(ctx: Context, l: IptvList): String {
        val s = statuses[l.id]
        val f = cacheFile(ctx, l.id)
        if (!s.isNullOrEmpty()) {
            return if (s.startsWith("Erro") && f.exists()) "$s (usando a versão salva)" else s
        }
        if (!f.exists()) return "Ainda não baixada"
        val n = loadCached(ctx, l)?.size ?: 0
        val date = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(f.lastModified()))
        return "$n canais ao vivo · atualizada em $date"
    }

    // ---------- download ----------

    /** Baixa a lista em segundo plano. [onDone] é chamado (em outra thread) ao terminar. */
    fun refreshAsync(ctx: Context, l: IptvList, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!busy.add(l.id)) return
        statuses[l.id] = "Baixando a lista de canais…"
        Thread {
            try {
                val raw = download(l)
                writeCache(app, l.id, raw)
                memo.remove(l.id)
                statuses[l.id] = ""
            } catch (e: Exception) {
                statuses[l.id] = "Erro: " + friendly(e)
            } finally {
                busy.remove(l.id)
                try {
                    onDone?.invoke()
                } catch (e: Exception) {
                    // ignora
                }
            }
        }.start()
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "sem internet ou endereço do servidor incorreto"
        is SocketTimeoutException -> "o servidor demorou demais para responder"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun writeCache(ctx: Context, id: String, raw: List<RawChannel>) {
        val tmp = File(ctx.filesDir, "list_$id.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            for (c in raw) {
                w.write(c.name)
                w.write("\t")
                w.write(c.url)
                w.write("\t")
                w.write(c.group)
                w.write("\n")
            }
        }
        val dest = cacheFile(ctx, id)
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("não consegui salvar a lista no aparelho")
    }

    private fun download(l: IptvList): List<RawChannel> {
        val raw = if (l.type == "xtream") downloadXtream(l) else downloadM3u(l.url)
        if (raw.isEmpty()) {
            throw IOException("a lista não tem canais ao vivo (confira o link, o usuário e a senha)")
        }
        return raw
    }

    private fun downloadM3u(url: String): List<RawChannel> =
        open(fixUrl(url)).inputStream.bufferedReader(Charsets.UTF_8).use { parseM3u(it) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fixUrl(u: String): String {
        val t = u.trim()
        return if (t.contains("://")) t else "http://$t"
    }

    private fun downloadXtream(l: IptvList): List<RawChannel> {
        val base = fixUrl(l.server).trimEnd('/')
        val u = enc(l.user)
        val p = enc(l.pass)
        val ext = if (l.hls) "m3u8" else "ts"
        val api = "$base/player_api.php?username=$u&password=$p"

        // 1) Jeito limpo: a API do servidor entrega só os canais ao vivo.
        try {
            val cats = HashMap<String, String>()
            try {
                val cj = JSONArray(readText("$api&action=get_live_categories"))
                for (i in 0 until cj.length()) {
                    val o = cj.getJSONObject(i)
                    cats[o.optString("category_id")] = o.optString("category_name")
                }
            } catch (e: Exception) {
                // categorias são opcionais
            }
            val arr = JSONArray(readText("$api&action=get_live_streams"))
            val out = ArrayList<RawChannel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("stream_id")
                val name = clean(o.optString("name"))
                if (id.isEmpty() || name.isEmpty()) continue
                out.add(RawChannel(name, "$base/live/$u/$p/$id.$ext", clean(cats[o.optString("category_id")] ?: "")))
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) {
            // cai para o segundo jeito
        }

        // 2) Plano B: baixar a lista M3U do servidor e filtrar.
        val m3u = "$base/get.php?username=$u&password=$p&type=m3u_plus&output=$ext"
        return downloadM3u(m3u)
    }

    private fun readText(url: String): String =
        open(url).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Abre a conexão seguindo redirecionamentos (inclusive de http para https). */
    private fun open(start: String): HttpURLConnection {
        var current = start
        for (i in 0 until 6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("redirecionamento inválido")
                current = URL(URL(current), loc).toString()
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("o servidor respondeu com erro $code")
            }
            return c
        }
        throw IOException("redirecionamentos demais")
    }

    // ---------- leitura de M3U ----------

    fun parseM3u(br: BufferedReader): List<RawChannel> {
        val out = ArrayList<RawChannel>()
        var name: String? = null
        var group = ""
        while (true) {
            val line = br.readLine() ?: break
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#EXTINF", ignoreCase = true)) {
                // O nome vem depois da vírgula que fica depois do último atributo (tvg-id="...", etc.)
                val lastAttrEnd = ATTR_RE.findAll(t).lastOrNull()?.range?.last?.plus(1) ?: 0
                val comma = t.indexOf(',', lastAttrEnd)
                var n = if (comma >= 0) t.substring(comma + 1).trim() else ""
                if (n.isEmpty()) n = TVGNAME_RE.find(t)?.groupValues?.get(1)?.trim() ?: ""
                name = n
                group = GROUP_RE.find(t)?.groupValues?.get(1)?.trim() ?: ""
            } else if (t.startsWith("#")) {
                if (name != null && t.startsWith("#EXTGRP:", ignoreCase = true)) {
                    group = t.substring(8).trim()
                }
            } else if (name != null) {
                val low = t.lowercase(Locale.ROOT)
                if (low.startsWith("http://") || low.startsWith("https://")) {
                    val path = low.substringBefore('?')
                    val vod = path.contains("/movie/") || path.contains("/series/") ||
                        path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi")
                    if (!vod && name.isNotEmpty()) out.add(RawChannel(clean(name), t, clean(group)))
                }
                name = null
                group = ""
            }
        }
        return out
    }

    private fun clean(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
