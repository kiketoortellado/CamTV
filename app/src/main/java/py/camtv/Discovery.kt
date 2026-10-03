package py.camtv

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Un equipo encontrado en la red. */
data class Found(
    val ip: String,
    var mac: String = "",
    var serial: String = "",
    var model: String = ""
) {
    fun describe(): String {
        val extra = listOf(model, mac.uppercase()).filter { it.isNotBlank() }.joinToString(" · ")
        return if (extra.isBlank()) ip else "$ip   ($extra)"
    }
}

fun normMac(m: String) = m.trim().lowercase().replace('-', ':')

/** Utilidades de red local. */
object Net {

    /** IP del Fire Stick en la red local y su prefijo (ej. 192.168.1.20 /24). */
    fun localIpv4(): Pair<Inet4Address, Int>? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifaces.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) return a to ia.networkPrefixLength.toInt()
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Todas las IPs de la subred (máximo un /24 para que sea rápido). */
    fun subnetHosts(): List<String> {
        val (addr, prefixRaw) = localIpv4() ?: return emptyList()
        val prefix = if (prefixRaw in 24..30) prefixRaw else 24
        val b = addr.address
        val ipInt = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        val mask = (-1 shl (32 - prefix))
        val net = ipInt and mask
        val count = (1 shl (32 - prefix)) - 2
        val own = addr.hostAddress
        return (1..count).map { i ->
            val v = net + i
            "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"
        }.filter { it != own }
    }

    /** IPs con el puerto abierto (escaneo en paralelo, ~2-4 segundos). */
    suspend fun scanPort(hosts: List<String>, port: Int, timeoutMs: Int = 400): List<String> = coroutineScope {
        val sem = Semaphore(48)
        hosts.map { h ->
            async(Dispatchers.IO) {
                sem.withPermit { if (RtspProbe.isPortOpen(h, port, timeoutMs)) h else null }
            }
        }.awaitAll().filterNotNull()
    }

    /** Tabla ARP (IP -> MAC). Funciona en Fire OS 5-7; en Fire OS 8 puede venir vacía. */
    fun arpTable(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[3] != "00:00:00:00:00:00") out[p[0]] = normMac(p[3])
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/**
 * Búsqueda de cámaras Dahua/Imou con su protocolo propio (DHIP, UDP 37810).
 * Las cámaras responden con su IP, MAC y número de serie: así se reconoce
 * cada cámara aunque el router le haya dado otra IP.
 */
object DhDiscovery {
    private const val PORT = 37810
    private const val GROUP = "239.255.255.251"

    fun search(context: Context, timeoutMs: Long = 2500): List<Found> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("camtv")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
            null
        }
        val result = LinkedHashMap<String, Found>()
        var sock: MulticastSocket? = null
        try {
            val s = try {
                MulticastSocket(null as SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            } catch (_: Exception) {
                MulticastSocket()
            }
            sock = s
            val group = InetAddress.getByName(GROUP)
            try { s.joinGroup(group) } catch (_: Exception) {}
            s.broadcast = true
            s.soTimeout = 300

            val packet = buildRequest()
            val targets = listOf(group, InetAddress.getByName("255.255.255.255"))
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            var sends = 0
            val buf = ByteArray(16384)
            while (System.currentTimeMillis() < deadline) {
                if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                    for (t in targets) {
                        try { s.send(DatagramPacket(packet, packet.size, t, PORT)) } catch (_: Exception) {}
                    }
                    sends++
                    nextSend = System.currentTimeMillis() + 700
                }
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    s.receive(dp)
                    parse(dp.data, dp.length, dp.address?.hostAddress)?.let { f ->
                        result[f.ip] = f
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }
        return result.values.toList()
    }

    private fun buildRequest(): ByteArray {
        val json = "{\"method\":\"DHDiscover.search\",\"params\":{\"mac\":\"\",\"uni\":1}}\n".toByteArray()
        val bb = ByteBuffer.allocate(32 + json.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x20)
        bb.put("DHIP".toByteArray())
        bb.putInt(0)          // session id
        bb.putInt(0)          // request id
        bb.putInt(json.size)
        bb.putInt(0)
        bb.putInt(json.size)
        bb.putInt(0)
        bb.put(json)
        return bb.array()
    }

    private fun parse(data: ByteArray, len: Int, fromIp: String?): Found? {
        return try {
            val text = String(data, 0, len, Charsets.UTF_8)
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = JSONObject(text.substring(start, end + 1))
            if (o.optString("method") == "DHDiscover.search") return null // nuestro propio pedido
            val info = o.optJSONObject("params")?.optJSONObject("deviceInfo") ?: return null
            val ip = info.optJSONObject("IPv4Address")?.optString("IPAddress")?.takeIf { it.isNotBlank() }
                ?: fromIp ?: return null
            Found(
                ip = ip,
                mac = normMac(info.optString("Mac")),
                serial = info.optString("SerialNo"),
                model = info.optString("DeviceType").ifBlank { info.optString("DeviceClass") }
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Busca todo lo que parezca una cámara: DHIP + puerto 554 abierto + tabla ARP. */
object NetworkScan {
    suspend fun discoverAll(context: Context, rtspPort: Int = 554): LinkedHashMap<String, Found> = coroutineScope {
        val dh = async(Dispatchers.IO) { DhDiscovery.search(context) }
        val open = async { Net.scanPort(Net.subnetHosts(), rtspPort) }
        val map = LinkedHashMap<String, Found>()
        dh.await().forEach { map[it.ip] = it }
        open.await().forEach { ip -> map.getOrPut(ip) { Found(ip) } }
        val arp = Net.arpTable() // después del escaneo la tabla ARP está completa
        map.values.forEach { f -> if (f.mac.isBlank()) arp[f.ip]?.let { f.mac = it } }
        map
    }
}
