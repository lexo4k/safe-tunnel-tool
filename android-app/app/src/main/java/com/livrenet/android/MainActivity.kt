package com.livrenet.android

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var connectButton: Button
    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var config: Config? = null
    @Volatile private var connected = false
    @Volatile private var connectAfterPermission = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = "LivreNet"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread {
                connected = newState == Tunnel.State.UP
                renderState()
            }
        }
    }

    private val profilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val stream = requireNotNull(contentResolver.openInputStream(uri)) {
                "Não foi possível abrir o perfil selecionado."
            }
            stream.use { config = Config.parse(it) }
            profileText.text = "Perfil WireGuard carregado na memória do app."
            statusText.text = "Perfil pronto. Toque em Conectar."
            connectButton.isEnabled = true
            connectButton.text = "Conectar"
        } catch (error: Exception) {
            config = null
            profileText.text = "Nenhum perfil válido carregado."
            statusText.text = "Não foi possível ler esse perfil: ${error.message ?: "formato inválido"}"
            connectButton.isEnabled = false
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && connectAfterPermission) {
            connectAfterPermission = false
            connectTunnel()
        } else {
            connectAfterPermission = false
            statusText.text = "Permissão VPN não concedida. Nenhum túnel foi iniciado."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        buildScreen()
        renderState()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 28)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "LivreNet"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 18)
        }
        profileText = TextView(this).apply {
            text = "Nenhum perfil carregado."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        val importButton = Button(this).apply {
            text = "Importar perfil WireGuard (.conf)"
            setOnClickListener {
                profilePicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            }
        }
        connectButton = Button(this).apply {
            text = "Conectar"
            isEnabled = false
            setOnClickListener {
                if (connected) disconnectTunnel() else requestVpnPermission()
            }
        }

        val note = TextView(this).apply {
            text = "O app não exige conta LivreNet. Para usar uma VPN real, importe um perfil WireGuard de um servidor que você tenha autorização para usar. O perfil fica apenas na memória desta sessão e não é enviado ao LivreNet."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(profileText)
        root.addView(importButton, matchWidth())
        root.addView(connectButton, matchWidth())
        root.addView(note)
        setContentView(root)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun requestVpnPermission() {
        val permissionIntent: Intent? = VpnService.prepare(this)
        if (permissionIntent == null) {
            connectTunnel()
        } else {
            connectAfterPermission = true
            vpnPermission.launch(permissionIntent)
        }
    }

    private fun connectTunnel() {
        val selectedConfig = config
        if (selectedConfig == null) {
            statusText.text = "Importe um perfil WireGuard válido primeiro."
            return
        }
        statusText.text = "Conectando ao servidor definido no perfil…"
        connectButton.isEnabled = false
        executor.execute {
            try {
                backend.setState(tunnel, Tunnel.State.UP, selectedConfig)
                runOnUiThread {
                    connected = true
                    statusText.text = "Túnel iniciado. A conexão depende do servidor e das chaves do perfil."
                    renderState()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    connected = false
                    statusText.text = "Falha ao conectar: ${error.message ?: "verifique o perfil e o servidor"}"
                    renderState()
                }
            }
        }
    }

    private fun disconnectTunnel() {
        statusText.text = "Desconectando…"
        connectButton.isEnabled = false
        executor.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread {
                    connected = false
                    statusText.text = "Desconectado."
                    renderState()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Não foi possível confirmar a desconexão: ${error.message ?: "erro desconhecido"}"
                    renderState()
                }
            }
        }
    }

    private fun renderState() {
        if (!::connectButton.isInitialized) return
        connectButton.isEnabled = config != null
        connectButton.text = if (connected) "Desconectar" else "Conectar"
        if (connected) statusText.text = "Conectado ao túnel WireGuard."
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
