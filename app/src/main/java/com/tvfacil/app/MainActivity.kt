package com.tvfacil.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListAdapter
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Tela da TV: só o vídeo.
 *
 *  - Digitar o número do canal (ex.: 1 2) → espera 2,5 s (ou aperta OK) e troca.
 *  - CH+ / CH- (ou seta para cima / para baixo) → próximo / anterior.
 *  - 0 sozinho → volta ao canal anterior.
 *  - OK → mostra o número e o nome do canal.
 *  - Segurar OK (ou tecla MENU) → menu para quem configura.
 */
class MainActivity : Activity() {

    companion object {
        private const val TYPE_WAIT_MS = 2500L   // tempo esperando o próximo dígito
        private const val ZAP_DELAY_MS = 350L    // evita abrir vários canais ao apertar CH+ rápido
        private const val INFO_MS = 4000L
        private const val STALE_MS = 12L * 60 * 60 * 1000 // atualiza a lista a cada 12 h
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var tvTyped: TextView
    private lateinit var infoBox: View
    private lateinit var tvInfoNumber: TextView
    private lateinit var tvInfoName: TextView
    private lateinit var tvMessage: TextView

    private var lineup = Lineup.EMPTY
    private var current: Channel? = null
    private var previousNumber = 0
    private val typed = StringBuilder()
    private var retries = 0
    private var errorShowing = false
    private var lastBackAt = 0L
    private var visible = false

    private val commitRunnable = Runnable { commitTyped() }
    private val hideInfoRunnable = Runnable { infoBox.visibility = View.GONE }
    private val hideMessageRunnable = Runnable { tvMessage.visibility = View.GONE }
    private val startRunnable = Runnable { current?.let { startPlayback(it) } }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        tvTyped = findViewById(R.id.typed)
        infoBox = findViewById(R.id.info)
        tvInfoNumber = findViewById(R.id.infoNumber)
        tvInfoName = findViewById(R.id.infoName)
        tvMessage = findViewById(R.id.message)

        initPlayer()
        previousNumber = Store.getPrev(this)

        // Primeira vez: ainda não há nenhuma lista, então já abre a configuração.
        if (Store.getLists(this).isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        loadLineup(restart = true)
        refreshIfStale()
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacksAndMessages(null)
        typed.clear()
        tvTyped.visibility = View.GONE
        player.stop() // ao voltar, recomeça "ao vivo", sem imagem atrasada
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ player

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (current != null && !errorShowing) showMessage("Carregando…")
                Player.STATE_READY -> {
                    retries = 0
                    errorShowing = false
                    hideMessage()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Playlist.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 1500, 3000)
            .build()
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        player.addListener(listener)
        playerView.player = player
    }

    private fun startPlayback(ch: Channel) {
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun handleError(e: PlaybackException) {
        val ch = current ?: return
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        retries++
        if (retries <= 3) {
            showMessage("Sem sinal neste canal.\nTentando de novo… ($retries de 3)")
            handler.postDelayed({ if (current === ch) startPlayback(ch) }, 3000)
        } else {
            errorShowing = true
            showMessage("Este canal não está funcionando agora.\nEscolha outro canal.")
        }
    }

    // ------------------------------------------------------------------ canais

    private fun loadLineup(restart: Boolean) {
        Thread {
            val l = Channels.loadActive(this)
            runOnUiThread { if (visible) applyLineup(l, restart) }
        }.start()
    }

    private fun applyLineup(l: Lineup, restart: Boolean) {
        lineup = l
        if (l.all.isEmpty()) {
            current = null
            val active = Store.getActive(this)
            when {
                active == null ->
                    showMessage("Bem-vindo!\nSegure o botão OK para configurar as listas de canais.")
                Playlist.anyBusy() -> {
                    showMessage("Baixando a lista de canais…\nAguarde um instante.")
                    handler.postDelayed({ loadLineup(false) }, 2000)
                }
                else -> {
                    val err = Playlist.lastError(active.id)
                    showMessage(
                        "Não foi possível carregar os canais." +
                            (if (err != null) "\n$err" else "") +
                            "\nSegure o botão OK para abrir o menu."
                    )
                }
            }
            return
        }
        val cur = current
        if (cur == null) {
            val target = l.byNumber[Store.getLast(this)] ?: l.zap.first()
            play(target, 0)
        } else if (restart) {
            play(cur, 0)
        }
    }

    private fun refreshIfStale() {
        val l = Store.getActive(this) ?: return
        val f = Playlist.cacheFile(this, l.id)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > STALE_MS) {
            Playlist.refreshAsync(this, l) {
                runOnUiThread { if (visible) loadLineup(false) }
            }
        }
    }

    private fun play(ch: Channel, delayMs: Long) {
        val prev = current
        if (prev != null && prev.number != ch.number) {
            previousNumber = prev.number
            Store.setPrev(this, previousNumber)
        }
        current = ch
        Store.setLast(this, ch.number)
        retries = 0
        errorShowing = false
        hideMessage()
        showInfo(ch)
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, delayMs)
    }

    private fun goToNumber(n: Int) {
        if (n == 0) {
            goPrevious()
            return
        }
        val ch = lineup.byNumber[n]
        if (ch == null) showMessage("O canal $n não existe", 2500) else play(ch, ZAP_DELAY_MS)
    }

    private fun goPrevious() {
        val p = lineup.byNumber[previousNumber]
        if (p == null) showMessage("Ainda não há canal anterior", 2000) else play(p, ZAP_DELAY_MS)
    }

    private fun zap(dir: Int) {
        val list = lineup.zap
        if (list.isEmpty()) return
        val cur = current
        val next: Channel = if (cur == null) {
            if (dir > 0) list.first() else list.last()
        } else {
            val idx = list.indexOfFirst { it.number == cur.number }
            if (idx >= 0) {
                list[(idx + dir + list.size) % list.size]
            } else if (dir > 0) {
                list.firstOrNull { it.number > cur.number } ?: list.first()
            } else {
                list.lastOrNull { it.number < cur.number } ?: list.last()
            }
        }
        play(next, ZAP_DELAY_MS)
    }

    // ------------------------------------------------------------------ número digitado

    private fun onDigit(d: Int) {
        if (lineup.all.isEmpty()) {
            showMessage("Nenhuma lista de canais.\nSegure o botão OK para configurar.", 3000)
            return
        }
        if (typed.length >= 4) typed.clear()
        typed.append(d)
        tvTyped.text = typed.toString()
        tvTyped.visibility = View.VISIBLE
        handler.removeCallbacks(commitRunnable)
        if (typed.length >= lineup.maxDigits) commitTyped() else handler.postDelayed(commitRunnable, TYPE_WAIT_MS)
    }

    private fun commitTyped() {
        handler.removeCallbacks(commitRunnable)
        if (typed.isEmpty()) return
        val n = typed.toString().toIntOrNull() ?: 0
        typed.clear()
        tvTyped.visibility = View.GONE
        goToNumber(n)
    }

    private fun clearTyped() {
        handler.removeCallbacks(commitRunnable)
        typed.clear()
        tvTyped.visibility = View.GONE
    }

    // ------------------------------------------------------------------ mensagens na tela

    private fun showInfo(ch: Channel) {
        tvInfoNumber.text = ch.number.toString()
        tvInfoName.text = ch.name
        infoBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfoRunnable)
        handler.postDelayed(hideInfoRunnable, INFO_MS)
    }

    private fun showMessage(text: String, ms: Long = 0) {
        tvMessage.text = text
        tvMessage.visibility = View.VISIBLE
        handler.removeCallbacks(hideMessageRunnable)
        if (ms > 0) handler.postDelayed(hideMessageRunnable, ms)
    }

    private fun hideMessage() {
        handler.removeCallbacks(hideMessageRunnable)
        tvMessage.visibility = View.GONE
    }

    // ------------------------------------------------------------------ controle remoto

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                onDigit(keyCode - KeyEvent.KEYCODE_0)
                return true
            }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                event.startTracking() // para distinguir toque curto de "segurar"
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.repeatCount == 0) zap(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.repeatCount == 0) zap(-1)
                return true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (event.repeatCount == 0) goPrevious()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (event.repeatCount == 0) openMenu()
                return true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                if (event.repeatCount == 0) openChannelList(lineup.hasFavorites)
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (event.repeatCount == 0) current?.let { showInfo(it) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.repeatCount == 0) handleBack()
                return true
            }
            // Setas laterais e teclas de reproduzir/pausar: ignoradas (é TV ao vivo).
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (event.isTracking && !event.isCanceled) onOk()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            openMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun onOk() {
        if (typed.isNotEmpty()) {
            commitTyped()
            return
        }
        val cur = current
        if (cur != null) showInfo(cur) else openMenu()
    }

    private fun handleBack() {
        if (typed.isNotEmpty()) {
            clearTyped()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 3000) {
            finish()
        } else {
            lastBackAt = now
            showMessage("Aperte VOLTAR de novo para sair do app", 3000)
        }
    }

    // ------------------------------------------------------------------ menu (para quem configura)

    private fun bigAdapter(items: List<String>): ListAdapter =
        object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 26f
                v.setPadding(32, 24, 32, 24)
                return v
            }
        }

    private fun openMenu() {
        clearTyped()
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (lineup.hasFavorites) items.add("Meus canais" to { openChannelList(true) })
        items.add("Todos os canais" to { openChannelList(false) })
        items.add("Digitar o número de um canal" to { askNumber() })
        items.add("Configurar listas de canais" to { startActivity(Intent(this, SettingsActivity::class.java)) })
        items.add("Fechar" to { })
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setAdapter(bigAdapter(items.map { it.first })) { _, i -> items[i].second() }
            .show()
    }

    private fun openChannelList(favoritesOnly: Boolean) {
        val src = if (favoritesOnly) lineup.zap else lineup.all
        if (src.isEmpty()) {
            showMessage("Nenhum canal disponível.", 2500)
            return
        }
        val labels = src.map { "${it.number}    ${it.name}" }
        val curNumber = current?.number
        val startIdx = src.indexOfFirst { it.number == curNumber }.coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (favoritesOnly) "Meus canais" else "Todos os canais")
            .setAdapter(bigAdapter(labels)) { _, i -> play(src[i], 0) }
            .create()
        dialog.show()
        dialog.listView.setSelection(startIdx)
    }

    private fun askNumber() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.textSize = 32f
        et.hint = "Número do canal"
        et.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("Número do canal")
            .setView(et)
            .setPositiveButton("Ir") { _, _ ->
                val n = et.text.toString().trim().toIntOrNull()
                if (n != null) goToNumber(n)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
