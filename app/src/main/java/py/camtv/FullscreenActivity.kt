package py.camtv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

/** Una cámara en pantalla completa. Izquierda/derecha cambia de cámara. */
class FullscreenActivity : Activity(), StreamPlayer.Listener {

    private val scope = MainScope()
    private lateinit var store: CameraStore
    private lateinit var player: StreamPlayer
    private lateinit var status: TextView
    private lateinit var label: TextView
    private var slot = 0
    private var cam: Camera? = null
    private var job: Job? = null
    private var backoff = 3000L
    private val hideLabel = Runnable { label.animate().alpha(0f).setDuration(600).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_fullscreen)
        store = CameraStore(this)
        status = findViewById(R.id.status)
        label = findViewById(R.id.label)
        player = StreamPlayer(findViewById<VLCVideoLayout>(R.id.video), this)
        slot = intent.getIntExtra("slot", 0)
    }

    override fun onStart() {
        super.onStart()
        load(slot)
    }

    override fun onStop() {
        job?.cancel()
        player.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load(s: Int) {
        slot = s
        backoff = 3000L
        cam = store.get(s)
        val c = cam ?: run { finish(); return }
        label.text = c.displayName
        label.alpha = 1f
        label.removeCallbacks(hideLabel)
        label.postDelayed(hideLabel, 4000)
        play()
    }

    private fun play() {
        val c = cam ?: return
        status.text = "Conectando…"
        val sub = if (c.lowQualityFull) 1 else 0
        player.start(c.rtspUrl(sub), c.user, c.password, audio = true)
    }

    override fun onPlaying() {
        status.text = ""
        backoff = 3000L
    }

    override fun onFailed() {
        player.stop()
        job?.cancel()
        job = scope.launch {
            status.text = "Sin señal — buscando la cámara…"
            val found = slot in Resolver.resolve(this@FullscreenActivity, listOf(slot))
            cam = store.get(slot)
            if (!found) status.text = "No encuentro la cámara.\nReintento en ${backoff / 1000} s"
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
            if (found) play() else onFailed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            else -> 0
        }
        if (step != 0) {
            val configured = store.all().map { it.slot }
            if (configured.size > 1) {
                val pos = configured.indexOf(slot).coerceAtLeast(0)
                val next = configured[(pos + step + configured.size) % configured.size]
                job?.cancel()
                player.stop()
                load(next)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
