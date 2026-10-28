package com.tvfacil.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Tela de quem configura (não é a dos pais): cadastrar, escolher e excluir listas.
 * Também liga o "servidor do celular" para facilitar a digitação de links e senhas.
 */
class SettingsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private lateinit var tvHelp: TextView
    private var server: ConfigServer? = null
    private var lastSignature = ""
    private var ticking = false

    private class Row(val list: IptvList, val active: Boolean, val status: String)

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            refreshNow()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        container = findViewById(R.id.lists)
        tvHelp = findViewById(R.id.help)
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        startServer()
        ticking = true
        lastSignature = ""
        handler.post(tick)
    }

    override fun onStop() {
        ticking = false
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onStop()
    }

    // ------------------------------------------------------------------ configuração pelo celular

    private fun startServer() {
        // Um código novo a cada vez que a tela abre.
        val pin = (100000..999999).random().toString()
        val s = ConfigServer(applicationContext, pin)
        if (!s.start()) {
            tvHelp.text = "Não foi possível ativar a configuração pelo celular.\nUse os botões abaixo."
            return
        }
        server = s
        val ip = localIp()
        tvHelp.text = if (ip != null) {
            "Mais fácil: configure pelo celular (ligado no mesmo Wi-Fi da TV).\n" +
                "Abra o navegador do celular e digite:\n\n" +
                "     http://$ip:${s.port}\n\n" +
                "Código de acesso:  $pin"
        } else {
            "A TV não parece estar conectada à internet (Wi-Fi ou cabo).\n" +
                "Conecte-a para poder configurar pelo celular."
        }
    }

    private fun localIp(): String? {
        return try {
            val found = ArrayList<Pair<String, String>>()
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        found.add(ni.name to ip)
                    }
                }
            }
            val best = found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") }
                ?: found.firstOrNull()
            best?.second
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ lista de listas

    private fun refreshNow() {
        Thread {
            val active = Store.activeId(this)
            val rows = Store.getLists(this).map { Row(it, it.id == active, Playlist.statusText(this, it)) }
            runOnUiThread { if (ticking && !isDestroyed) applyRows(rows) }
        }.start()
    }

    private fun applyRows(rows: List<Row>) {
        val sig = rows.joinToString("|") { "${it.list.id}:${it.list.name}:${it.active}:${it.status}" }
        if (sig == lastSignature) return
        lastSignature = sig

        val focusedTag = currentFocus?.tag
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(label("Nenhuma lista ainda. Use o botão abaixo (ou o celular).", 22f, "#B0BEC5"))
        }
        for (r in rows) container.addView(buildRow(r))
        if (focusedTag != null) container.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    private fun buildRow(r: Row): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundResource(R.drawable.bg_card)
        box.setPadding(dp(20), dp(16), dp(20), dp(16))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        box.layoutParams = lp

        val title = (if (r.active) "● " else "○ ") + r.list.name + (if (r.active) "   (em uso)" else "")
        box.addView(label(title, 26f, if (r.active) "#FFC107" else "#FFFFFF"))
        box.addView(label(r.status, 20f, "#B0BEC5"))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.START
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(10)
        buttons.layoutParams = blp

        if (!r.active) {
            buttons.addView(button("Usar esta lista", "use:${r.list.id}") {
                Store.setActive(this, r.list.id)
                refreshNow()
            })
        }
        buttons.addView(button("Atualizar", "upd:${r.list.id}") {
            Playlist.refreshAsync(this, r.list)
            refreshNow()
        })
        buttons.addView(button("Excluir", "del:${r.list.id}") { confirmDelete(r.list) })
        box.addView(buttons)
        return box
    }

    private fun confirmDelete(l: IptvList) {
        AlertDialog.Builder(this)
            .setTitle("Excluir lista?")
            .setMessage(l.name)
            .setPositiveButton("Excluir") { _, _ ->
                Store.removeList(this, l.id)
                refreshNow()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ adicionar lista

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_list, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val rbXtream = view.findViewById<RadioButton>(R.id.rbXtream)
        val boxM3u = view.findViewById<View>(R.id.boxM3u)
        val boxX = view.findViewById<View>(R.id.boxX)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etServer = view.findViewById<EditText>(R.id.etServer)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPass = view.findViewById<EditText>(R.id.etPass)
        val cbHls = view.findViewById<CheckBox>(R.id.cbHls)

        view.findViewById<RadioButton>(R.id.rbM3u).setOnCheckedChangeListener { _, checked ->
            if (checked) {
                boxM3u.visibility = View.VISIBLE
                boxX.visibility = View.GONE
            }
        }
        rbXtream.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                boxM3u.visibility = View.GONE
                boxX.visibility = View.VISIBLE
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Adicionar lista de canais")
            .setView(view)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.show()

        // Sobrescreve o botão "Salvar" para não fechar o diálogo quando falta algum campo.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val xtream = rbXtream.isChecked
            val name = etName.text.toString().trim().ifEmpty { "Lista ${Store.getLists(this).size + 1}" }
            val url = etUrl.text.toString().trim()
            val srv = etServer.text.toString().trim()
            val usr = etUser.text.toString().trim()
            val pwd = etPass.text.toString().trim()

            if (!xtream && url.isEmpty()) {
                Toast.makeText(this, "Digite o link da lista", Toast.LENGTH_LONG).show()
            } else if (xtream && (srv.isEmpty() || usr.isEmpty() || pwd.isEmpty())) {
                Toast.makeText(this, "Preencha servidor, usuário e senha", Toast.LENGTH_LONG).show()
            } else {
                val list = IptvList(
                    Store.newId(), name, if (xtream) "xtream" else "m3u",
                    url, srv, usr, pwd, cbHls.isChecked
                )
                Store.addList(this, list)
                Playlist.refreshAsync(this, list)
                dialog.dismiss()
                refreshNow()
            }
        }
    }

    // ------------------------------------------------------------------ utilidades de tela

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun label(text: String, sizeSp: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(android.graphics.Color.parseColor(color))
        return t
    }

    private fun button(text: String, tag: String, onClick: () -> Unit): Button {
        val b = Button(this, null, 0, R.style.TvButton)
        b.text = text
        b.tag = tag
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(12)
        b.layoutParams = lp
        return b
    }
}
