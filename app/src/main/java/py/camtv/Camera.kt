package py.camtv

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val SLOT_COUNT = 4

/** Una cámara guardada. La identidad estable es mac/serial; la IP puede cambiar. */
data class Camera(
    val slot: Int,
    var name: String,
    var ip: String,
    var port: Int = 554,
    var user: String = "admin",
    var password: String = "",
    var channel: Int = 1,
    var mac: String = "",
    var serial: String = "",
    var lowQualityFull: Boolean = false
) {
    /** subtype 0 = calidad alta, 1 = calidad baja (substream). Formato Dahua/Imou. */
    fun rtspPath(subtype: Int) = "/cam/realmonitor?channel=$channel&subtype=$subtype"
    fun rtspUrl(subtype: Int) = "rtsp://$ip:$port${rtspPath(subtype)}"
    val displayName get() = name.ifBlank { "Cámara ${slot + 1}" }
}

/** Guarda las cámaras en el almacenamiento privado de la app (contraseña cifrada). */
class CameraStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    fun get(slot: Int): Camera? {
        val raw = prefs.getString("cam_$slot", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Camera(
                slot = slot,
                name = o.optString("name"),
                ip = o.optString("ip"),
                port = o.optInt("port", 554),
                user = o.optString("user", "admin"),
                password = Crypto.decrypt(o.optString("pw")),
                channel = o.optInt("channel", 1),
                mac = o.optString("mac"),
                serial = o.optString("serial"),
                lowQualityFull = o.optBoolean("lowFull", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun all(): List<Camera> = (0 until SLOT_COUNT).mapNotNull { get(it) }

    @Synchronized
    fun save(c: Camera) {
        val o = JSONObject()
            .put("name", c.name)
            .put("ip", c.ip)
            .put("port", c.port)
            .put("user", c.user)
            .put("pw", Crypto.encrypt(c.password))
            .put("channel", c.channel)
            .put("mac", c.mac)
            .put("serial", c.serial)
            .put("lowFull", c.lowQualityFull)
        prefs.edit().putString("cam_${c.slot}", o.toString()).apply()
    }

    fun delete(slot: Int) {
        prefs.edit().remove("cam_$slot").apply()
    }
}

/** Cifrado AES-GCM con una clave del Android Keystore (Fire OS 6+). */
object Crypto {
    private const val ALIAS = "camtv_key"

    fun encrypt(plain: String): String {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return encryptGcm(plain)
            } catch (_: Exception) {
            }
        }
        return "p:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        return try {
            when {
                stored.startsWith("g:") && Build.VERSION.SDK_INT >= 23 -> decryptGcm(stored)
                stored.startsWith("p:") -> String(Base64.decode(stored.substring(2), Base64.NO_WRAP))
                else -> stored
            }
        } catch (_: Exception) {
            ""
        }
    }

    @TargetApi(23)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    @TargetApi(23)
    private fun encryptGcm(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.doFinal(plain.toByteArray())
        return "g:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(out, Base64.NO_WRAP)
    }

    @TargetApi(23)
    private fun decryptGcm(stored: String): String {
        val parts = stored.split(":")
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val data = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data))
    }
}
