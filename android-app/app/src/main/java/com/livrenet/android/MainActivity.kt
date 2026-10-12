package com.livrenet.android

import android.app.Activity
import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var connectButton: Button
    private lateinit var freeVpnButton: Button
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
            profileText.text = "Nenhum perfil WireGuard válido carregado."
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
            text = "Escolha uma forma de conexão para começar."
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

        freeVpnButton = Button(this).apply {
            text = "Buscar servidor gratuito"
            setOnClickListener { findFreeVpnGateServer() }
        }

        val importButton = Button(this).apply {
            text = "Importar perfil WireGuard (.conf)"
            setOnClickListener {
                profilePicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            }
        }
        connectButton = Button(this).apply {
            text = "Conectar WireGuard"
            isEnabled = false
            setOnClickListener {
                if (connected) disconnectTunnel() else requestVpnPermission()
            }
        }

        val note = TextView(this).apply {
            text = "A busca gratuita usa servidores públicos voluntários do VPN Gate. Para esses servidores, o LivreNet baixa uma configuração OpenVPN e a envia a um aplicativo OpenVPN compatível. A conexão OpenVPN não é executada dentro desta versão do LivreNet. Servidores públicos podem falhar, registrar tráfego ou mudar sem aviso."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(profileText)
        root.addView(freeVpnButton, matchWidth())
        root.addView(importButton, matchWidth())
        root.addView(connectButton, matchWidth())
        root.addView(note)
        setContentView(root)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun findFreeVpnGateServer() {
        freeVpnButton.isEnabled = false
        statusText.text = "Buscando servidores públicos disponíveis…"
        profileText.text = "Consultando a lista pública do VPN Gate."
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "LivreNet-Android/0.1")
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val candidates = body.lineSequence()
                    .filter { line ->
                        line.isNotBlank() && !line.startsWith("#") && !line.startsWith("*") &&
                            line.count { it == ',' } >= 14
                    }
                    .mapNotNull { line ->
                        try {
                            val firstFields = line.split(",", limit = 6)
                            val speed = firstFields.getOrNull(4)?.toLongOrNull() ?: 0L
                            val encodedConfig = line.substringAfterLast(',').trim().trim('"')
                            val bytes = Base64.getDecoder().decode(encodedConfig)
                            val ovpn = bytes.toString(Charsets.UTF_8)
                            if (ovpn.contains("client") && ovpn.contains("remote ")) speed to ovpn else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .sortedByDescending { it.first }
                    .toList()

                val selected = candidates.firstOrNull()
                    ?: throw IllegalStateException("A lista não trouxe uma configuração OpenVPN utilizável. Tente novamente mais tarde.")
                val profileFile = File(cacheDir, "livrenet-vpngate.ovpn")
                profileFile.writeText(selected.second, Charsets.UTF_8)
                val profileUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    profileFile
                )
                runOnUiThread {
                    freeVpnButton.isEnabled = true
                    statusText.text = "Configuração baixada. Escolha um aplicativo OpenVPN para continuar."
                    profileText.text = "Perfil público obtido do VPN Gate. O LivreNet ainda não conecta OpenVPN internamente."
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-openvpn-profile"
                        putExtra(Intent.EXTRA_STREAM, profileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(shareIntent, "Abrir perfil com um cliente OpenVPN"))
                    } catch (error: Exception) {
                        statusText.text = "Perfil baixado, mas não foi possível abrir o menu de compartilhamento: ${error.message ?: "erro desconhecido"}"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    freeVpnButton.isEnabled = true
                    statusText.text = "Não foi possível buscar um perfil agora: ${error.message ?: "verifique a internet e tente novamente"}"
                    profileText.text = "Nenhuma conexão VPN foi iniciada."
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

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
                    statusText.text = "Túnel WireGuard iniciado. A conectividade depende do servidor e das chaves do perfil."
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
        connectButton.text = if (connected) "Desconectar WireGuard" else "Conectar WireGuard"
        if (connected) statusText.text = "Túnel WireGuard conectado."
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
