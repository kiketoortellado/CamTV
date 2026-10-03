package py.camtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

/** Pantalla principal: cuadrícula de 4 cámaras. */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: CameraStore
    private val slots = ArrayList<Slot>()
    private var startJob: Job? = null

    inner class Slot(val index: Int, val root: FrameLayout) : StreamPlayer.Listener {
        private val video: VLCVideoLayout = root.findViewById(R.id.video)
        private val label: TextView = root.findViewById(R.id.label)
        val status: TextView = root.findViewById(R.id.status)
        val player = StreamPlayer(video, this)
        var cam: Camera? = null
        var retryJob: Job? = null
        private var backoff = 3000L

        fun bind(c: Camera?) {
            cam = c
            if (c == null) {
                label.visibility = android.view.View.GONE
                status.text = getString(R.string.empty_slot)
            } else {
                label.visibility = android.view.View.VISIBLE
                label.text = c.displayName
                status.text = "Conectando…"
            }
        }

        fun play() {
            val c = cam ?: return
            status.text = "Conectando…"
            // en la cuadrícula se usa la calidad baja: más fluido y aguanta 4 a la vez
            player.start(c.rtspUrl(1), c.user, c.password, audio = false)
        }

        override fun onPlaying() {
            status.text = ""
            backoff = 3000L
        }

        override fun onFailed() {
            player.stop()
            retry(searchFirst = true)
        }

        /** Busca la cámara (por si cambió de IP) y vuelve a conectar, con espera creciente. */
        fun retry(searchFirst: Boolean) {
            retryJob?.cancel()
            retryJob = scope.launch {
                var found = false
                if (searchFirst) {
                    status.text = "Sin señal — buscando la cámara…"
                    found = index in Resolver.resolve(this@MainActivity, listOf(index))
                    cam = store.get(index)
                    cam?.let { label.text = it.displayName }
                }
                if (!found) status.text = "No encuentro la cámara.\nReintento en ${backoff / 1000} s"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
                if (found) play() else retry(searchFirst = true)
            }
        }

        fun release() {
            retryJob?.cancel()
            player.stop()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        store = CameraStore(this)

        val rows = listOf(findViewById<LinearLayout>(R.id.row1), findViewById<LinearLayout>(R.id.row2))
        val inflater = LayoutInflater.from(this)
        for (i in 0 until SLOT_COUNT) {
            val row = rows[i / 2]
            val root = inflater.inflate(R.layout.view_slot, row, false) as FrameLayout
            row.addView(root)
            val slot = Slot(i, root)
            root.setOnClickListener {
                if (slot.cam != null) openFullscreen(i) else openEditor(i)
            }
            root.setOnLongClickListener { openEditor(i); true }
            slots += slot
        }
        slots[0].root.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        slots.forEach { it.bind(store.get(it.index)) }
        val configured = slots.filter { it.cam != null }
        if (configured.isEmpty()) return
        configured.forEach { it.status.text = "Buscando cámara…" }
        startJob = scope.launch {
            val ok = Resolver.resolve(this@MainActivity, configured.map { it.index })
            for (s in configured) {
                s.bind(store.get(s.index))
                if (s.index in ok) s.play() else s.retry(searchFirst = false)
            }
        }
    }

    override fun onStop() {
        startJob?.cancel()
        slots.forEach { it.release() }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = slots.indexOfFirst { it.root.hasFocus() }.coerceAtLeast(0)
            openEditor(focused)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openFullscreen(slot: Int) {
        startActivity(Intent(this, FullscreenActivity::class.java).putExtra("slot", slot))
    }

    private fun openEditor(slot: Int) {
        startActivity(Intent(this, EditActivity::class.java).putExtra("slot", slot))
    }
}
