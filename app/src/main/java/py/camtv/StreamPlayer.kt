package py.camtv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Reproduce un stream RTSP en un VLCVideoLayout y avisa si se cae:
 * error de VLC, fin del stream, sin video a los 20 s o imagen congelada.
 */
class StreamPlayer(private val layout: VLCVideoLayout, private val listener: Listener) {

    interface Listener {
        fun onPlaying()
        fun onFailed()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var gotVideo = false
    private var failed = false
    private var lastTime = -1L
    private var frozenChecks = 0

    private val startWatchdog = Runnable { if (!gotVideo) fail() }

    private val stallWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val t = p.time
            if (gotVideo) {
                if (t == lastTime || !p.isPlaying) frozenChecks++ else frozenChecks = 0
                lastTime = t
                if (frozenChecks >= 2) { fail(); return }
            }
            handler.postDelayed(this, 6000)
        }
    }

    fun start(url: String, user: String, pass: String, audio: Boolean) {
        stop()
        val vlc = App.instance.libVLC
        val p = MediaPlayer(vlc)
        p.attachViews(layout, null, false, false)

        val m = Media(vlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":rtsp-user=$user")
        m.addOption(":rtsp-pwd=$pass")
        m.addOption(":rtsp-tcp")
        m.addOption(":network-caching=800")
        if (!audio) m.addOption(":no-audio")
        p.setMedia(m)
        m.release()

        gotVideo = false
        failed = false
        lastTime = -1L
        frozenChecks = 0

        p.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                when (event.type) {
                    MediaPlayer.Event.Vout -> if (event.voutCount > 0 && !gotVideo) {
                        gotVideo = true
                        handler.removeCallbacks(startWatchdog)
                        handler.post { listener.onPlaying() }
                    }
                    MediaPlayer.Event.EncounteredError,
                    MediaPlayer.Event.EndReached -> fail()
                }
            }
        })
        player = p
        p.play()
        handler.postDelayed(startWatchdog, 20_000)
        handler.postDelayed(stallWatchdog, 10_000)
    }

    private fun fail() {
        if (failed) return
        failed = true
        handler.removeCallbacks(startWatchdog)
        handler.removeCallbacks(stallWatchdog)
        handler.post { listener.onFailed() }
    }

    fun stop() {
        handler.removeCallbacks(startWatchdog)
        handler.removeCallbacks(stallWatchdog)
        player?.let {
            it.setEventListener(null)
            try {
                it.stop()
                it.detachViews()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }
}
