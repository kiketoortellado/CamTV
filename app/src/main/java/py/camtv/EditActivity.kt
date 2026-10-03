package py.camtv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Alta / edición de una cámara: nombre, IP, usuario y contraseña. */
class EditActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: CameraStore
    private var slot = 0
    private var existing: Camera? = null
    private var lastScan: Map<String, Found> = emptyMap()

    private lateinit var name: EditText
    private lateinit var ip: EditText
    private lateinit var port: EditText
    private lateinit var channel: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var lowQuality: CheckBox
    private lateinit var status: TextView
    private lateinit var buttons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        store = CameraStore(this)
        slot = intent.getIntExtra("slot", 0)
        existing = store.get(slot)

        name = findViewById(R.id.name)
        ip = findViewById(R.id.ip)
        port = findViewById(R.id.port)
        channel = findViewById(R.id.channel)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        lowQuality = findViewById(R.id.lowQuality)
        status = findViewById(R.id.editStatus)
        val btnSearch = findViewById<Button>(R.id.btnSearch)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnDelete = findViewById<Button>(R.id.btnDelete)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        buttons = listOf(btnSearch, btnSave, btnDelete, btnCancel)

        findViewById<TextView>(R.id.title).text =
            if (existing == null) "Agregar cámara (posición ${slot + 1})" else "Editar cámara (posición ${slot + 1})"

        existing?.let { c ->
            name.setText(c.name)
            ip.setText(c.ip)
            port.setText(c.port.toString())
            channel.setText(c.channel.toString())
            user.setText(c.user)
            password.setText(c.password)
            lowQuality.isChecked = c.lowQualityFull
        }
        btnDelete.visibility = if (existing == null) View.GONE else View.VISIBLE

        findViewById<CheckBox>(R.id.showPassword).setOnCheckedChangeListener { _, show ->
            password.inputType = InputType.TYPE_CLASS_TEXT or
                (if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            password.setSelection(password.text.length)
        }

        btnSearch.setOnClickListener { search() }
        btnSave.setOnClickListener { save() }
        btnCancel.setOnClickListener { finish() }
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar esta cámara?")
                .setPositiveButton("Eliminar") { _, _ -> store.delete(slot); finish() }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        if (existing == null) name.requestFocus() else btnSave.requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun busy(msg: String?) {
        status.text = msg ?: ""
        buttons.forEach { it.isEnabled = msg == null }
    }

    /** Busca cámaras en la red y deja elegir una. */
    private fun search() {
        busy("Buscando cámaras en la red… (unos segundos)")
        scope.launch {
            val p = port.text.toString().toIntOrNull() ?: 554
            val found = withContext(Dispatchers.IO) { NetworkScan.discoverAll(this@EditActivity, p) }
            lastScan = found
            busy(null)
            if (found.isEmpty()) {
                status.text = "No se encontraron cámaras. ¿El Fire Stick está en la misma red WiFi que las cámaras?"
                return@launch
            }
            val usedIps = store.all().filter { it.slot != slot }.associate { it.ip to it.displayName }
            val items = found.values.map { f ->
                val used = usedIps[f.ip]?.let { "   ← ya usada: $it" } ?: ""
                f.describe() + used
            }.toTypedArray()
            AlertDialog.Builder(this@EditActivity)
                .setTitle("Equipos encontrados (${found.size})")
                .setItems(items) { _, which ->
                    ip.setText(found.values.elementAt(which).ip)
                    status.text = "IP elegida. Completá la contraseña y presioná «Probar y guardar»."
                    password.requestFocus()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun readForm(): Camera? {
        val ipText = ip.text.toString().trim()
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ipText)) {
            status.text = "Ingresá una IP válida (ej. 192.168.1.50) o usá «Buscar en la red»."
            ip.requestFocus()
            return null
        }
        if (password.text.isEmpty()) {
            status.text = "Falta la contraseña (el Safety Code de la etiqueta de la cámara)."
            password.requestFocus()
            return null
        }
        val old = existing
        return Camera(
            slot = slot,
            name = name.text.toString().trim(),
            ip = ipText,
            port = port.text.toString().toIntOrNull() ?: 554,
            user = user.text.toString().trim().ifEmpty { "admin" },
            password = password.text.toString(),
            channel = channel.text.toString().toIntOrNull() ?: 1,
            // si cambió la IP a mano se vuelve a identificar la cámara
            mac = if (old != null && old.ip == ipText) old.mac else "",
            serial = if (old != null && old.ip == ipText) old.serial else "",
            lowQualityFull = lowQuality.isChecked
        )
    }

    /** Prueba usuario/contraseña, guarda la identidad (MAC/serie) y cierra. */
    private fun save() {
        val cam = readForm() ?: return
        busy("Probando conexión con ${cam.ip}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { Resolver.check(cam, cam.ip) }
            when (result) {
                RtspProbe.Result.OK -> {
                    status.text = "¡Conectado! Guardando…"
                    val id = lastScan[cam.ip]?.takeIf { it.mac.isNotBlank() }
                        ?: Resolver.identify(this@EditActivity, cam.ip)
                    id?.let {
                        if (it.mac.isNotBlank()) cam.mac = it.mac
                        if (it.serial.isNotBlank()) cam.serial = it.serial
                    }
                    store.save(cam)
                    finish()
                }
                RtspProbe.Result.AUTH_FAIL -> {
                    busy(null)
                    status.text = "Usuario o contraseña incorrectos. La contraseña es el «Safety Code» " +
                        "de la etiqueta (o la que hayas puesto si la cambiaste)."
                    password.requestFocus()
                }
                RtspProbe.Result.NOT_RTSP -> {
                    busy(null)
                    AlertDialog.Builder(this@EditActivity)
                        .setTitle("La cámara no respondió")
                        .setMessage(
                            "No hubo respuesta RTSP en ${cam.ip}:${cam.port}.\n\n" +
                                "Puede estar apagada, tener otra IP o tener el RTSP desactivado.\n" +
                                "¿Guardar igual? La app la buscará sola en la red cuando aparezca."
                        )
                        .setPositiveButton("Guardar igual") { _, _ -> store.save(cam); finish() }
                        .setNegativeButton("Volver", null)
                        .show()
                }
            }
        }
    }
}
