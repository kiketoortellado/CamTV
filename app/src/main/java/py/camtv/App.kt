package py.camtv

import android.app.Application
import org.videolan.libvlc.LibVLC

class App : Application() {

    /** Una sola instancia de VLC compartida por todos los reproductores. */
    val libVLC: LibVLC by lazy {
        LibVLC(
            this,
            arrayListOf(
                "--rtsp-tcp",            // RTSP sobre TCP: más estable por WiFi
                "--network-caching=800",
                "--drop-late-frames",
                "--skip-frames",
                "--no-stats"
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
