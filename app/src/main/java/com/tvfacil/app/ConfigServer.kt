package com.tvfacil.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Mini servidor que roda NA TV, só enquanto a tela "Configurar listas" está aberta.
 * Permite configurar tudo pelo navegador do celular (digitar link/senha no controle é muito ruim).
 * Protegido por um código de 6 dígitos que aparece na TV.
 */
class ConfigServer(private val ctx: Context, private val pin: String) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    private var running = false

    @Volatile
    private var failures = 0

    var port = 0
        private set

    fun start(): Boolean {
        for (p in intArrayOf(8080, 8081, 8888, 0)) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                serverSocket = s
                port = s.localPort
                break
            } catch (e: IOException) {
                // tenta a próxima porta
            }
        }
        val s = serverSocket ?: return false
        running = true
        Thread {
            while (running) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }.start()
        return true
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignora
        }
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------ HTTP básico

    private fun handle(sock: Socket) {
        sock.use {
            try {
                sock.soTimeout = 10000
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]

                var length = 0
                var reqPin = ""
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) {
                        val k = h.substring(0, i).trim().lowercase()
                        val v = h.substring(i + 1).trim()
                        if (k == "content-length") length = v.toIntOrNull() ?: 0
                        if (k == "x-pin") reqPin = v
                    }
                }
                if (length > 1_000_000) {
                    respond(out, 413, "text/plain; charset=utf-8", "Muito grande".toByteArray())
                    return
                }
                val body = if (length > 0) readExactly(input, length) else ByteArray(0)
                route(out, method, target, reqPin, String(body, Charsets.UTF_8))
            } catch (e: Exception) {
                // conexão caiu ou pedido inválido: ignora
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readExactly(i: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, code: Int, obj: Any) =
        respond(out, code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

    private fun err(out: OutputStream, code: Int, msg: String) =
        json(out, code, JSONObject().put("error", msg))

    private fun param(query: String, name: String): String {
        for (kv in query.split('&')) {
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i) == name) {
                return try {
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                } catch (e: Exception) {
                    ""
                }
            }
        }
        return ""
    }

    // ------------------------------------------------------------------ rotas

    private fun route(out: OutputStream, method: String, target: String, reqPin: String, body: String) {
        val path = target.substringBefore('?')
        val query = if (target.contains('?')) target.substringAfter('?') else ""

        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = ctx.assets.open("index.html").use { it.readBytes() }
            respond(out, 200, "text/html; charset=utf-8", html)
            return
        }
        if (!path.startsWith("/api/")) {
            respond(out, 404, "text/plain; charset=utf-8", "Não encontrado".toByteArray())
            return
        }
        if (failures >= 20) {
            err(out, 429, "Muitas tentativas erradas. Feche e abra de novo a tela de configuração na TV.")
            return
        }
        if (reqPin != pin) {
            failures++
            err(out, 401, "Código incorreto")
            return
        }

        try {
            when {
                method == "GET" && path == "/api/state" -> json(out, 200, state())
                method == "GET" && path == "/api/channels" -> json(out, 200, search(param(query, "q")))
                method == "POST" && path == "/api/lists" -> addList(out, JSONObject(body))
                method == "POST" && path == "/api/lists/delete" -> {
                    Store.removeList(ctx, JSONObject(body).optString("id"))
                    json(out, 200, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/lists/active" -> {
                    Store.setActive(ctx, JSONObject(body).optString("id"))
                    json(out, 200, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/lists/refresh" -> {
                    val id = JSONObject(body).optString("id")
                    Store.getLists(ctx).firstOrNull { it.id == id }?.let { Playlist.refreshAsync(ctx, it) }
                    json(out, 200, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/number" -> setNumber(out, JSONObject(body))
                else -> err(out, 404, "Não encontrado")
            }
        } catch (e: Exception) {
            err(out, 500, e.message ?: "Erro interno")
        }
    }

    private fun state(): JSONObject {
        val active = Store.activeId(ctx)
        val lists = JSONArray()
        for (l in Store.getLists(ctx)) {
            lists.put(
                JSONObject()
                    .put("id", l.id)
                    .put("name", l.name)
                    .put("type", l.type)
                    .put("active", l.id == active)
                    .put("busy", Playlist.isBusy(l.id))
                    .put("status", Playlist.statusText(ctx, l))
            )
        }
        val favs = JSONArray()
        for (f in Store.getFavs(ctx).sortedBy { it.number }) {
            favs.put(JSONObject().put("name", f.name).put("number", f.number))
        }
        return JSONObject().put("lists", lists).put("favorites", favs)
    }

    private fun addList(out: OutputStream, o: JSONObject) {
        val type = if (o.optString("type") == "xtream") "xtream" else "m3u"
        val name = o.optString("name").trim().ifEmpty { "Lista ${Store.getLists(ctx).size + 1}" }
        val l = IptvList(
            Store.newId(), name, type,
            o.optString("url").trim(),
            o.optString("server").trim(),
            o.optString("user").trim(),
            o.optString("pass").trim(),
            o.optBoolean("hls")
        )
        if (type == "m3u" && l.url.isEmpty()) {
            err(out, 400, "Informe o link da lista")
            return
        }
        if (type == "xtream" && (l.server.isEmpty() || l.user.isEmpty() || l.pass.isEmpty())) {
            err(out, 400, "Informe servidor, usuário e senha")
            return
        }
        Store.addList(ctx, l)
        Playlist.refreshAsync(ctx, l)
        json(out, 200, JSONObject().put("ok", true))
    }

    private fun search(q: String): JSONArray {
        val terms = Channels.norm(q).split(' ').filter { it.isNotEmpty() }
        val res = JSONArray()
        if (terms.isEmpty()) return res
        val lineup = Channels.loadActive(ctx)
        var count = 0
        for (c in lineup.all) {
            val hay = Channels.norm(c.name + " " + c.group)
            if (terms.all { hay.contains(it) }) {
                res.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("group", c.group)
                        .put("number", if (c.favorite) c.number else 0)
                )
                if (++count >= 100) break
            }
        }
        return res
    }

    private fun setNumber(out: OutputStream, o: JSONObject) {
        val name = o.optString("name")
        val num = o.optInt("number", 0)
        if (name.isEmpty()) {
            err(out, 400, "Canal inválido")
            return
        }
        if (num < 0 || num > 999) {
            err(out, 400, "Use um número de 1 a 999")
            return
        }
        val key = Channels.key(name)
        val favs = Store.getFavs(ctx).filter { Channels.key(it.name) != key }.toMutableList()
        if (num > 0) {
            val clash = favs.firstOrNull { it.number == num }
            if (clash != null) {
                err(out, 409, "O número $num já é do canal \"${clash.name}\". Tire-o de lá primeiro.")
                return
            }
            favs.add(Fav(name, num))
        }
        Store.setFavs(ctx, favs)
        json(out, 200, JSONObject().put("ok", true))
    }
}
