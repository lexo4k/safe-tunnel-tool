package com.livrenet.android

import android.app.Activity
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var internetText: TextView
    private lateinit var profileText: TextView
    private lateinit var connectButton: Button
    private lateinit var freeVpnButton: Button
    private lateinit var backend: GoBackend
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var config: Config? = null
    @Volatile private var connected = false
    @Volatile private var connectAfterPermission = false

    private data class PublicServer(
        val host: String,
        val ip: String,
        val country: String,
        val speed: Long,
        val ovpn: String
    )

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
            statusText.text = "Perfil pronto. Toque em Conectar WireGuard."
            connectButton.isEnabled = true
            connectButton.text = "Conectar WireGuard"
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
        refreshInternetStatus()
        renderState()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.livrenet_logo)
            contentDescription = "Logotipo LivreNet"
            layoutParams = LinearLayout.LayoutParams(88, 88).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 8
            }
        }
        val title = TextView(this).apply {
            text = "LivreNet"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Sua conexão, com transparência"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 12)
        }
        internetText = TextView(this).apply {
            text = "Verificando internet…"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12, 12, 12, 12)
        }
        statusText = TextView(this).apply {
            text = "Escolha uma forma de conexão para começar."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 14)
        }
        profileText = TextView(this).apply {
            text = "Nenhum perfil WireGuard carregado."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 16)
        }

        val checkInternetButton = Button(this).apply {
            text = "Verificar internet"
            setOnClickListener { refreshInternetStatus() }
        }
        freeVpnButton = Button(this).apply {
            text = "Buscar servidores públicos"
            setOnClickListener { findFreeVpnGateServers() }
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
            text = "Importante: a busca pública encontra perfis OpenVPN e os encaminha para um cliente OpenVPN instalado. Essa versão ainda não executa OpenVPN dentro do LivreNet. Para conectar pelo botão WireGuard, importe um perfil WireGuard válido. Servidores públicos podem ficar offline e não garantem internet grátis ou ilimitada."
            textSize = 12f
            setPadding(0, 18, 0, 0)
        }

        root.addView(logo)
        root.addView(title)
        root.addView(subtitle)
        root.addView(internetText, matchWidth())
        root.addView(statusText, matchWidth())
        root.addView(profileText, matchWidth())
        root.addView(checkInternetButton, matchWidth())
        root.addView(freeVpnButton, matchWidth())
        root.addView(importButton, matchWidth())
        root.addView(connectButton, matchWidth())
        root.addView(note)
        setContentView(root)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun refreshInternetStatus() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        internetText.text = if (hasInternet) {
            "Internet de base: disponível"
        } else {
            "Internet de base: sem acesso confirmado"
        }
        if (!hasInternet) {
            statusText.text = "Sem internet de base. As configurações salvas continuam disponíveis, mas a busca e a conexão com servidores remotos podem falhar."
        } else if (!connected) {
            statusText.text = "Internet disponível. Escolha um servidor ou importe um perfil WireGuard."
        }
    }

    private fun findFreeVpnGateServers() {
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
                    setRequestProperty("User-Agent", "LivreNet-Android/0.3")
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val candidates = body.lineSequence()
                    .filter { line ->
                        line.isNotBlank() && !line.startsWith("#") && !line.startsWith("*") &&
                            line.count { it == ',' } >= 14
                    }
                    .mapNotNull { line ->
                        try {
                            val fields = line.split(",", limit = 15)
                            if (fields.size < 15) return@mapNotNull null
                            val ip = fields[1].trim().trim('"')
                            val speed = fields[4].trim().trim('"').toLongOrNull() ?: 0L
                            val country = fields[5].trim().trim('"').ifBlank { "País desconhecido" }
                            val encodedConfig = fields[14].trim().trim('"')
                            val ovpn = String(Base64.decode(encodedConfig, Base64.DEFAULT), Charsets.UTF_8)
                            if (ip.isBlank() || !ovpn.contains("client") || !ovpn.contains("remote ")) {
                                null
                            } else {
                                PublicServer(
                                    host = fields[0].trim().trim('"').ifBlank { ip },
                                    ip = ip,
                                    country = country,
                                    speed = speed,
                                    ovpn = ovpn
                                )
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .distinctBy { it.ip }
                    .sortedByDescending { it.speed }
                    .take(20)
                    .toList()

                runOnUiThread {
                    freeVpnButton.isEnabled = true
                    if (candidates.isEmpty()) {
                        statusText.text = "A lista pública não retornou perfis utilizáveis. Tente novamente mais tarde."
                        profileText.text = "Nenhum servidor foi selecionado."
                    } else {
                        showServerPicker(candidates)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    freeVpnButton.isEnabled = true
                    statusText.text = "Falha ao buscar servidores: ${error.message ?: "verifique a internet e tente novamente"}"
                    profileText.text = "Nenhuma conexão VPN foi iniciada."
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun showServerPicker(servers: List<PublicServer>) {
        val labels = servers.map { server ->
            "${server.country} • ${server.ip}\nVelocidade anunciada: ${server.speed / 1_000_000} Mbps"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolha um servidor público")
            .setItems(labels) { _, which -> shareOpenVpnProfile(servers[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
        statusText.text = "${servers.size} opções encontradas. Selecione uma para preparar o perfil."
        profileText.text = "A lista é pública e pode conter servidores indisponíveis."
    }

    private fun shareOpenVpnProfile(server: PublicServer) {
        try {
            val profileFile = File(cacheDir, "livrenet-${server.ip.replace('.', '-')}.ovpn")
            profileFile.writeText(server.ovpn, Charsets.UTF_8)
            val profileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", profileFile)
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(profileUri, "application/x-openvpn-profile")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            statusText.text = "Perfil de ${server.country} preparado. Selecione um cliente OpenVPN compatível para importar e conectar."
            profileText.text = "Servidor escolhido: ${server.ip}. O túnel OpenVPN ainda não é executado dentro do LivreNet."
            try {
                startActivity(openIntent)
            } catch (_: android.content.ActivityNotFoundException) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/x-openvpn-profile"
                    putExtra(Intent.EXTRA_STREAM, profileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Importar perfil OpenVPN"))
            }
        } catch (error: Exception) {
            statusText.text = "Não foi possível compartilhar o perfil: ${error.message ?: "erro desconhecido"}"
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
