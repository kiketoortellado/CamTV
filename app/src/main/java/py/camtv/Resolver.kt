package py.camtv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Solución a la IP dinámica.
 *
 * Para cada cámara:
 *  1. Prueba la última IP conocida con su usuario/contraseña.
 *  2. Si no responde, busca en la red (DHIP de Dahua/Imou + escaneo del puerto 554).
 *  3. Si la cámara tiene MAC/serie guardada, va directo a la IP con esa MAC.
 *  4. Si no, prueba sus credenciales en los equipos candidatos: como cada Imou
 *     tiene su propio Safety Code, solo la cámara correcta acepta la contraseña.
 *  5. Guarda la IP nueva para la próxima vez.
 */
object Resolver {
    private val mutex = Mutex()
    private const val MAX_ATTEMPTS = 10 // evita bloquear la cuenta de la cámara por intentos fallidos

    fun check(c: Camera, ip: String): RtspProbe.Result =
        RtspProbe.checkAuth(ip, c.port, c.user, c.password, c.rtspPath(1))

    /** Devuelve los slots cuyas cámaras quedaron ubicadas (IP verificada). */
    suspend fun resolve(context: Context, slots: Collection<Int>): Set<Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val store = CameraStore(context)
            val all = store.all()
            val targets = all.filter { it.slot in slots }
            val ok = HashSet<Int>()
            val claimed = HashSet<String>()

            // 1. ¿Sigue en la misma IP?
            for (c in targets) {
                if (c.ip.isNotBlank() && check(c, c.ip) == RtspProbe.Result.OK) {
                    ok += c.slot
                    claimed += c.ip
                }
            }
            val pending = targets.filter { it.slot !in ok }
            if (pending.isEmpty()) return@withContext ok

            // 2. Buscar en la red
            val found = NetworkScan.discoverAll(context, pending.first().port)

            // 3-4. Ubicar cada cámara pendiente
            for (c in pending) {
                val otherIps = all.filter { it.slot != c.slot }.map { it.ip }.toSet()
                for (ip in candidates(c, found, claimed, otherIps).take(MAX_ATTEMPTS)) {
                    if (check(c, ip) == RtspProbe.Result.OK) {
                        c.ip = ip
                        found[ip]?.let { f ->
                            if (f.mac.isNotBlank()) c.mac = f.mac
                            if (f.serial.isNotBlank()) c.serial = f.serial
                        }
                        store.save(c) // 5. recordar la IP nueva
                        ok += c.slot
                        claimed += ip
                        break
                    }
                }
            }
            ok
        }
    }

    private fun candidates(
        c: Camera,
        found: Map<String, Found>,
        claimed: Set<String>,
        otherIps: Set<String>
    ): List<String> {
        val list = found.values.filter { it.ip !in claimed }
        val myMac = normMac(c.mac)
        val exact = list.filter {
            (myMac.isNotBlank() && it.mac == myMac) ||
                (c.serial.isNotBlank() && it.serial.equals(c.serial, true))
        }
        // descartar equipos que sabemos que son OTRA cámara (MAC distinta)
        val unknown = list.filter { it !in exact }
            .filter { myMac.isBlank() || it.mac.isBlank() || it.mac == myMac }
        val (others, rest) = unknown.partition { it.ip in otherIps }
        return (exact + rest + others).map { it.ip }.distinct()
    }

    /** Intenta averiguar MAC y número de serie de una IP (para guardarlos al configurar). */
    suspend fun identify(context: Context, ip: String): Found? = withContext(Dispatchers.IO) {
        DhDiscovery.search(context, 2000).firstOrNull { it.ip == ip }?.let { return@withContext it }
        Net.arpTable()[ip]?.let { Found(ip, mac = it) }
    }
}
