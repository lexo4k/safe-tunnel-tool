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
        val ink = android.graphics.Color.rgb(7, 17, 31)
        val panel = android.graphics.Color.rgb(15, 31, 49)
        val mint = android.graphics.Color.rgb(34, 211, 197)
        val white = android.graphics.Color.rgb(239, 246, 255)
        val muted = android.graphics.Color.rgb(157, 178, 199)

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel)
                cornerRadius = 22f
                setStroke(1, android.graphics.Color.rgb(35, 57, 78))
            }
        }
        fun label(textValue: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = textValue
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        fun styleButton(button: Button, primary: Boolean = false) {
            button.isAllCaps = false
            button.textSize = 15f
            button.setTextColor(if (primary) ink else white)
            button.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) mint else android.graphics.Color.rgb(26, 45, 65))
                cornerRadius = 16f
                if (!primary) setStroke(1, android.graphics.Color.rgb(50, 76, 99))
            }
            button.minHeight = 54
            button.setPadding(16, 12, 16, 12)
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ink)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 22, 20, 28)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.livrenet_logo)
            contentDescription = "Logotipo LivreNet, proteção e conexão global"
            layoutParams = LinearLayout.LayoutParams(68, 68).apply { rightMargin = 14 }
        }
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(label("LIVRENET", 26f, white, true))
        brandText.addView(label("PROTEÇÃO • CONEXÃO GLOBAL", 11f, mint, true))
        brandRow.addView(logo)
        brandRow.addView(brandText)
        root.addView(brandRow)

        val hero = card().apply {
            setPadding(20, 20, 20, 20)
            val eyebrow = label("SUA CONEXÃO, SOB SEU CONTROLE", 11f, mint, true)
            addView(eyebrow)
            val headline = label("O mundo mais perto.", 25f, white, true).apply {
                setPadding(0, 8, 0, 6)
            }
            addView(headline)
            addView(label("Consulte a internet, explore servidores públicos e gerencie seu perfil VPN em um só lugar.", 14f, muted))
        }
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22 })

        val networkCard = card()
        networkCard.addView(label("ESTADO DA REDE", 11f, muted, true))
        internetText = label("Verificando internet…", 16f, white, true).apply {
            setPadding(0, 8, 0, 2)
        }
        networkCard.addView(internetText)
        statusText = label("Escolha uma opção para começar.", 13f, muted).apply {
            setPadding(0, 6, 0, 0)
        }
        networkCard.addView(statusText)
        root.addView(networkCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })

        root.addView(label("EXPLORAR E CONECTAR", 12f, mint, true).apply {
            setPadding(2, 24, 0, 10)
        })

        val serverCard = card()
        serverCard.addView(label("🌐  Servidores ao redor do mundo", 17f, white, true))
        serverCard.addView(label("Consulte a lista pública e escolha um perfil disponível. A disponibilidade varia por país e horário.", 13f, muted).apply {
            setPadding(0, 6, 0, 12)
        })
        freeVpnButton = Button(this).apply {
            text = "Buscar servidores públicos"
            styleButton(this, true)
            setOnClickListener { findFreeVpnGateServers() }
        }
        serverCard.addView(freeVpnButton, matchWidth())
        root.addView(serverCard)

        val modesCard = card()
        modesCard.addView(label("MODOS DE CONEXÃO", 12f, mint, true))
        modesCard.addView(label("WireGuard • disponível com perfil válido", 14f, white, true).apply {
            setPadding(0, 10, 0, 4)
        })
        modesCard.addView(label("OpenVPN • perfis públicos podem ser importados em um cliente externo. Motor integrado ainda não incluído.", 13f, muted).apply {
            setPadding(0, 2, 0, 8)
        })
        modesCard.addView(label("SSH Direct • SSH + Proxy • SSH + SSL • SSH + SSL Proxy", 14f, white, true))
        modesCard.addView(label("Esses quatro modos SSH ainda não estão implementados: precisam de transporte SSH/TLS real e de um servidor autorizado. Não serão apresentados como conectados sem um motor funcional.", 13f, muted).apply {
            setPadding(0, 6, 0, 0)
        })
        root.addView(modesCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })

        val wireguardCard = card().apply {
            setPadding(18, 18, 18, 18)
        }
        wireguardCard.addView(label("🔐  Conexão WireGuard", 17f, white, true))
        profileText = label("Nenhum perfil WireGuard carregado.", 13f, muted).apply {
            setPadding(0, 6, 0, 12)
        }
        wireguardCard.addView(profileText)

        val importButton = Button(this).apply {
            text = "Importar perfil WireGuard (.conf)"
            styleButton(this)
            setOnClickListener {
                profilePicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            }
        }
        wireguardCard.addView(importButton, matchWidth())
        connectButton = Button(this).apply {
            text = "Conectar WireGuard"
            isEnabled = false
            styleButton(this, true)
            setOnClickListener {
                if (connected) disconnectTunnel() else requestVpnPermission()
            }
        }
        wireguardCard.addView(connectButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
        root.addView(wireguardCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })

        val checkInternetButton = Button(this).apply {
            text = "Atualizar estado da internet"
            styleButton(this)
            setOnClickListener { refreshInternetStatus() }
        }
        root.addView(checkInternetButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })

        val note = label(
            "TRANSPARÊNCIA\nA busca global usa servidores públicos de terceiros. Os perfis encontrados são OpenVPN e precisam de um cliente OpenVPN compatível instalado. O botão WireGuard conecta apenas perfis WireGuard válidos. Esta versão ainda não executa OpenVPN dentro do LivreNet. Servidores públicos podem falhar e não garantem internet grátis ou ilimitada.",
            12f, muted
        ).apply { setPadding(4, 20, 4, 0) }
        root.addView(note)
        root.addView(label("LIVRENET  •  CONEXÃO COM TRANSPARÊNCIA", 10f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun refreshInternetStatus() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            caps == null -> "Rede: nenhuma conexão ativa detectada"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Rede: Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rede: dados móveis"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Rede: Ethernet"
            else -> "Rede: conexão ativa"
        }
        internetText.text = "$transport\\n" + if (hasInternet) {
            "Acesso geral à internet confirmado"
        } else {
            "Acesso à internet não confirmado"
        }
        if (!hasInternet) {
            statusText.text = "O LivreNet não conseguiu confirmar acesso à internet. Isso não informa se ainda há franquia no chip: o Android não fornece ao app uma verificação universal de saldo/pacote. Sem tráfego permitido pela operadora, um servidor VPN remoto pode não ser alcançável."
        } else if (!connected) {
            statusText.text = "Conexão de rede detectada. Isso não confirma que há franquia disponível nem garante acesso ao servidor VPN. Escolha um servidor ou importe um perfil WireGuard."
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
                val actualState = backend.getState(tunnel)
                runOnUiThread {
                    connected = actualState == Tunnel.State.UP
                    statusText.text = if (connected) {
                        "WireGuard ativo no Android. Acesso aos destinos depende do servidor, das chaves e das rotas do perfil."
                    } else {
                        "O pedido de conexão terminou, mas o backend não confirmou o túnel ativo. Confira o perfil e tente novamente."
                    }
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
