package py.camtv

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Cliente RTSP mínimo, solo para comprobar si en una IP hay una cámara que
 * acepta un usuario y contraseña (autenticación Digest o Basic).
 */
object RtspProbe {

    enum class Result { OK, AUTH_FAIL, NOT_RTSP }

    private class Response(val code: Int, val headers: List<Pair<String, String>>) {
        fun all(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
    }

    /** ¿Hay algo escuchando en ip:port? (sin enviar credenciales) */
    fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    /** Pide el stream con usuario/contraseña. OK = es la cámara correcta. */
    fun checkAuth(ip: String, port: Int, user: String, pass: String, path: String, timeoutMs: Int = 2500): Result {
        val uri = "rtsp://$ip:$port$path"
        var sock: Socket = try {
            open(ip, port, timeoutMs)
        } catch (_: Exception) {
            return Result.NOT_RTSP
        }
        try {
            val r1 = transact(sock, describe(uri, 1, null)) ?: return Result.NOT_RTSP
            if (r1.code == 200) return Result.OK
            if (r1.code != 401) return Result.NOT_RTSP
            val auth = buildAuth(r1.all("WWW-Authenticate"), user, pass, "DESCRIBE", uri)
                ?: return Result.AUTH_FAIL
            var r2 = try {
                transact(sock, describe(uri, 2, auth))
            } catch (_: Exception) {
                null
            }
            if (r2 == null) { // algunas cámaras cierran la conexión tras el 401
                sock.close()
                sock = open(ip, port, timeoutMs)
                r2 = transact(sock, describe(uri, 2, auth)) ?: return Result.NOT_RTSP
            }
            return when (r2.code) {
                200 -> Result.OK
                401, 403 -> Result.AUTH_FAIL
                else -> Result.NOT_RTSP
            }
        } catch (_: Exception) {
            return Result.NOT_RTSP
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun open(ip: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(ip, port), timeoutMs)
        s.soTimeout = timeoutMs
        return s
    }

    private fun describe(uri: String, cseq: Int, auth: String?): String {
        val sb = StringBuilder()
            .append("DESCRIBE ").append(uri).append(" RTSP/1.0\r\n")
            .append("CSeq: ").append(cseq).append("\r\n")
            .append("Accept: application/sdp\r\n")
            .append("User-Agent: CamTV\r\n")
        if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    private fun transact(sock: Socket, request: String): Response? {
        sock.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
        // sin buffer: así no se pierden bytes entre un pedido y el siguiente
        return readResponse(sock.getInputStream())
    }

    private fun readLine(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) break
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readResponse(inp: InputStream): Response? {
        val status = readLine(inp) ?: return null
        if (!status.startsWith("RTSP/")) return null
        val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: return null
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers += line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        // descartar el cuerpo para dejar la conexión lista para el siguiente pedido
        val len = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull() ?: 0
        var left = len
        while (left > 0) {
            val n = inp.skip(left.toLong()).toInt()
            if (n <= 0) { if (inp.read() < 0) break else left-- } else left -= n
        }
        return Response(code, headers)
    }

    private fun buildAuth(challenges: List<String>, user: String, pass: String, method: String, uri: String): String? {
        val digest = challenges.firstOrNull { it.startsWith("Digest", true) }
        if (digest != null) {
            val p = parseParams(digest.substring(6))
            val realm = p["realm"] ?: ""
            val nonce = p["nonce"] ?: ""
            val ha1 = md5("$user:$realm:$pass")
            val ha2 = md5("$method:$uri")
            val qop = p["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
            val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop != null) {
                val nc = "00000001"
                val cnonce = Random.nextLong().toULong().toString(16)
                val resp = md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
                sb.append(", response=\"$resp\", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            } else {
                sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
            }
            p["opaque"]?.let { sb.append(", opaque=\"$it\"") }
            p["algorithm"]?.let { sb.append(", algorithm=$it") }
            return sb.toString()
        }
        if (challenges.any { it.startsWith("Basic", true) }) {
            val token = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
            return "Basic $token"
        }
        return null
    }

    private fun parseParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]*))""").findAll(s).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groups[2]?.value ?: m.groupValues[3]
        }
        return out
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
