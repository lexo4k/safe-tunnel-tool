package com.livrenet.android

import android.app.Activity
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Build
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
import org.json.JSONArray
import org.json.JSONObject

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
    @Volatile private var openVpnProfileAfterPermission: String? = null
    @Volatile private var lastServerCandidates: List<PublicServer> = emptyList()
    @Volatile private var lastSelectedServerIp: String? = null
    @Volatile private var tlsDialogShowing = false

    private val openVpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(OpenVpnVpnService.EXTRA_STATUS) ?: return
            if (::statusText.isInitialized) statusText.text = message
            val lower = message.lowercase()
            val tlsOrAuthFailure = listOf(
                "unsupportedcertversion", "invalid peer certificate", "certificate verify failed",
                "certificate verification failed", "tls handshake", "tls error",
                "handshake failed", "no shared cipher", "peer certificate", "authentication failed",
                "auth_failed", "private key", "unsupported protocol"
            ).any { lower.contains(it) }
            if (tlsOrAuthFailure && !tlsDialogShowing) {
                if (::statusText.isInitialized) {
                    statusText.text = "Falha de compatibilidade TLS, certificado ou autenticação. A validação de segurança foi mantida; tente outro servidor ou perfil."
                }
                val alternatives = lastServerCandidates.filter { it.ip != lastSelectedServerIp }
                if (!isFinishing) showTlsCompatibilityDialog(alternatives)
            }
        }
    }

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
        val pendingOpenVpnProfile = openVpnProfileAfterPermission
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingOpenVpnProfile != null) {
                openVpnProfileAfterPermission = null
                statusText.text = "Permissão concedida. Iniciando o núcleo OpenVPN 3…"
                profileText.text = "Conectando diretamente pelo serviço VPN do LivreNet."
                OpenVpnVpnService.connect(this, pendingOpenVpnProfile)
            } else if (connectAfterPermission) {
                connectAfterPermission = false
                connectTunnel()
            }
        } else {
            openVpnProfileAfterPermission = null
            connectAfterPermission = false
            statusText.text = "Permissão VPN não concedida. Nenhum túnel foi iniciado."
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(OpenVpnVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(openVpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(openVpnStatusReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(openVpnStatusReceiver) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        buildScreen()
        lastServerCandidates = loadCachedServers()
        refreshInternetStatus()
        if (lastServerCandidates.isNotEmpty()) {
            statusText.text = "${lastServerCandidates.size} perfis OpenVPN salvos neste aparelho. Se estiver sem internet, tente abrir a lista salva; a conexão ainda precisa alcançar um servidor."
        }
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
            val eyebrow = label("VPN COM TRANSPARÊNCIA", 11f, mint, true)
            addView(eyebrow)
            val headline = label("Conecte. Verifique. Navegue.", 24f, white, true).apply {
                setPadding(0, 8, 0, 6)
            }
            addView(headline)
            addView(label("Uma experiência simples, com diagnóstico de conexão e estado da rede sem indicadores inventados.", 14f, muted))
        }
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22 })

        val networkCard = card()
        networkCard.addView(label("ESTADO DA REDE E DO TÚNEL", 11f, muted, true))
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
            text = "Buscar servidores OpenVPN"
            styleButton(this, true)
            setOnClickListener { findFreeVpnGateServers() }
        }
        serverCard.addView(freeVpnButton, matchWidth())
        val disconnectOpenVpnButton = Button(this).apply {
            text = "Desconectar OpenVPN"
            styleButton(this)
            setOnClickListener {
                OpenVpnVpnService.disconnect(this@MainActivity)
                statusText.text = "Solicitação de desconexão enviada ao serviço OpenVPN…"
            }
        }
        serverCard.addView(disconnectOpenVpnButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        root.addView(serverCard)

        val modesCard = card()
        modesCard.addView(label("MODOS DE CONEXÃO", 12f, mint, true))
        modesCard.addView(label("Escolha a tecnologia para sua conexão", 14f, white, true).apply {
            setPadding(0, 6, 0, 12)
        })

        fun modeRow(title: String, description: String, state: String, available: Boolean) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(19, 39, 58))
                    cornerRadius = 16f
                    setStroke(1, if (available) mint else android.graphics.Color.rgb(50, 76, 99))
                }
            }
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(label(title, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label(state, 10f, if (available) mint else muted, true))
            row.addView(heading)
            row.addView(label(description, 12f, muted).apply { setPadding(0, 5, 0, 0) })
            modesCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        }

        modeRow("OpenVPN • MOTOR PRINCIPAL", "Núcleo OpenVPN 3 integrado ao app. Certificados ou servidores públicos incompatíveis podem ser recusados com segurança; escolha outro servidor se isso ocorrer.", "INTEGRADO", true)
        modeRow("SSH Direct", "Ainda precisa de motor SSH e servidor autorizado.", "EM DESENVOLVIMENTO", false)
        modeRow("SSH + Proxy", "Ainda precisa de motor SSH/Proxy e servidor autorizado.", "EM DESENVOLVIMENTO", false)
        modeRow("SSH + SSL", "Ainda precisa de transporte TLS/SSH e servidor autorizado.", "EM DESENVOLVIMENTO", false)
        modeRow("SSH + SSL Proxy", "Ainda precisa de transporte TLS/SSH, proxy e servidor autorizado.", "EM DESENVOLVIMENTO", false)
        modeRow("WireGuard • OPÇÃO ALTERNATIVA", "Motor integrado para perfis WireGuard .conf válidos. Não conecta diretamente aos perfis OpenVPN da lista pública.", "INTEGRADO", true)
        root.addView(modesCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })

        val wireguardCard = card().apply {
            setPadding(18, 18, 18, 18)
        }
        wireguardCard.addView(label("🔐  Conexão alternativa • WireGuard", 17f, white, true))
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

        val diagnosticsButton = Button(this).apply {
            text = "Testar túnel e IP público"
            styleButton(this)
            setOnClickListener { runTunnelDiagnostics() }
        }
        root.addView(diagnosticsButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        val note = label(
            "SEGURANÇA E COMPATIBILIDADE\nO LivreNet pede a autorização VPN oficial do Android ao conectar pela primeira vez. O motor OpenVPN funciona dentro do app. Se um servidor tiver TLS, certificado, cifra ou autenticação incompatível, o app orienta a trocar de servidor sem desativar a validação de segurança. Perfis guardados podem ser exibidos offline, mas a conexão precisa de uma rede que alcance o servidor. Nenhum servidor garante internet móvel grátis ou ilimitada.",
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

    private fun runTunnelDiagnostics() {
        statusText.text = "Testando acesso HTTPS e consultando o IP público…"
        executor.execute {
            var ipConnection: HttpURLConnection? = null
            var probeConnection: HttpURLConnection? = null
            try {
                ipConnection = (URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val publicIp = ipConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                if (!publicIp.matches(Regex("[0-9a-fA-F:.]{3,45}"))) {
                    throw IllegalStateException("O serviço de IP retornou uma resposta inesperada.")
                }
                val diagnosticPrefs = getSharedPreferences("livrenet_diagnostics", Context.MODE_PRIVATE)
                val previousPublicIp = diagnosticPrefs.getString("last_public_ip", null)

                probeConnection = (URL("https://clients3.google.com/generate_204").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                }
                val code = probeConnection.responseCode
                val probeOk = code == 204 || (code in 200..299)
                diagnosticPrefs.edit().putString("last_public_ip", publicIp).apply()
                val ipComparison = when {
                    previousPublicIp.isNullOrBlank() ->
                        "Este é o primeiro IP registrado. Execute o teste antes e depois de conectar para comparar."
                    previousPublicIp != publicIp ->
                        "O IP difere do teste anterior ($previousPublicIp). Isso é compatível com uma saída de rede diferente, mas não comprova sozinho que todo o tráfego passa pelo túnel."
                    else ->
                        "O IP é igual ao teste anterior. Se você conectou a VPN entre os testes, a saída pelo túnel ainda não foi confirmada."
                }
                runOnUiThread {
                    statusText.text = if (probeOk) {
                        "Diagnóstico HTTPS concluído (HTTP $code). IP público atual: $publicIp. $ipComparison"
                    } else {
                        "IP público observado: $publicIp. O teste HTTPS respondeu HTTP $code. $ipComparison"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Diagnóstico não concluído: ${error.message ?: "falha de rede ou timeout"}. Isso não prova, sozinho, que o túnel caiu."
                }
            } finally {
                ipConnection?.disconnect()
                probeConnection?.disconnect()
            }
        }
    }

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
        internetText.text = "$transport\n" + if (hasInternet) {
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
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val internetValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val cached = lastServerCandidates.ifEmpty { loadCachedServers() }
        if (!internetValidated && cached.isNotEmpty()) {
            lastServerCandidates = cached
            statusText.text = "Sem internet validada. Abrindo ${cached.size} perfis salvos no aparelho; conectar ainda depende de alcançar o servidor."
            profileText.text = "Lista offline. Alguns servidores podem estar fora do ar ou ter mudado de configuração."
            showServerPicker(cached)
            return
        }
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

                if (candidates.isNotEmpty()) saveCachedServers(candidates)
                runOnUiThread {
                    freeVpnButton.isEnabled = true
                    if (candidates.isEmpty()) {
                        statusText.text = "A lista pública não retornou perfis utilizáveis. Tente novamente mais tarde."
                        profileText.text = "Nenhum servidor foi selecionado."
                    } else {
                        lastServerCandidates = candidates
                        showServerPicker(candidates)
                    }
                }
            } catch (error: Exception) {
                val cached = loadCachedServers()
                runOnUiThread {
                    freeVpnButton.isEnabled = true
                    if (cached.isNotEmpty()) {
                        lastServerCandidates = cached
                        statusText.text = "Sem acesso à lista online. Mostrando ${cached.size} perfis salvos. A conexão só funcionará se houver uma rede que alcance o servidor."
                        profileText.text = "Lista offline do aparelho. Perfis públicos podem ter expirado ou estar indisponíveis."
                        showServerPicker(cached)
                    } else {
                        statusText.text = "Falha ao buscar servidores: ${error.message ?: "verifique a internet e tente novamente"}. Nenhum perfil offline foi salvo."
                        profileText.text = "Nenhuma conexão VPN foi iniciada."
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun saveCachedServers(servers: List<PublicServer>) {
        runCatching {
            val array = JSONArray()
            servers.take(20).forEach { server ->
                array.put(JSONObject().apply {
                    put("host", server.host)
                    put("ip", server.ip)
                    put("country", server.country)
                    put("speed", server.speed)
                    put("ovpn", server.ovpn)
                })
            }
            getSharedPreferences("livrenet_offline", Context.MODE_PRIVATE)
                .edit().putString("public_openvpn_servers", array.toString()).apply()
        }
    }

    private fun loadCachedServers(): List<PublicServer> {
        return runCatching {
            val raw = getSharedPreferences("livrenet_offline", Context.MODE_PRIVATE)
                .getString("public_openvpn_servers", null) ?: return emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val host = item.optString("host")
                val ip = item.optString("ip")
                val country = item.optString("country", "País desconhecido")
                val ovpn = item.optString("ovpn")
                if (ip.isBlank() || ovpn.isBlank() || !ovpn.contains("remote ")) null
                else PublicServer(host, ip, country, item.optLong("speed", 0L), ovpn)
            }
                .distinctBy { it.ip }
                .sortedByDescending { it.speed }
                .take(20)
        }.getOrDefault(emptyList())
    }

    private fun showServerPicker(servers: List<PublicServer>) {
        if (servers.isEmpty()) {
            statusText.text = "Não há outros servidores na lista. Busque servidores novamente para atualizar as opções."
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "Escolha um servidor para conectar dentro do LivreNet. Se um certificado falhar, volte aqui e tente outro."
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(157, 178, 199))
            setPadding(0, 0, 0, dp(12))
        })

        val list = android.widget.LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        servers.take(20).forEachIndexed { index, server ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(15, 31, 49))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), android.graphics.Color.rgb(48, 75, 98))
                }
            }
            card.addView(TextView(this).apply {
                text = "${server.country}  •  ${server.ip}"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.rgb(239, 246, 255))
            })
            card.addView(TextView(this).apply {
                text = "Velocidade anunciada: ${server.speed / 1_000_000} Mbps  •  Perfil OpenVPN"
                textSize = 12f
                setTextColor(android.graphics.Color.rgb(157, 178, 199))
                setPadding(0, dp(5), 0, dp(10))
            })
            val connect = Button(this).apply {
                text = "Conectar"
                isAllCaps = false
                setTextColor(android.graphics.Color.rgb(7, 17, 31))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(34, 211, 197))
                    cornerRadius = dp(11).toFloat()
                }
                contentDescription = "Conectar à VPN pelo servidor ${server.country}, IP ${server.ip}"
                setOnClickListener {
                    isEnabled = false
                    text = "Validando perfil…"
                    serverDialog?.dismiss()
                    selectOpenVpnServer(server)
                }
            }
            card.addView(connect, LinearLayout.LayoutParams(-1, -2))
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(430)))
        serverDialog = AlertDialog.Builder(this)
            .setTitle("Servidores OpenVPN")
            .setView(panel)
            .setNegativeButton("Fechar", null)
            .create()
        serverDialog?.show()
        statusText.text = "${servers.size} servidores OpenVPN encontrados. Escolha Conectar no servidor desejado."
        profileText.text = "Servidores públicos de terceiros. Disponibilidade e certificados variam."
    }

    private var serverDialog: AlertDialog? = null

    private fun showTlsCompatibilityDialog(alternatives: List<PublicServer>) {
        if (tlsDialogShowing || isFinishing) return
        tlsDialogShowing = true
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "O servidor pode estar usando certificado, versão TLS, cifra ou autenticação incompatível. O LivreNet não desativa a validação de certificados. Tente outro servidor público ou atualize a lista."
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(65, 80, 96))
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("Servidor incompatível")
            .setView(panel)
            .setOnDismissListener { tlsDialogShowing = false }
            .create()
        val choose = Button(this).apply {
            text = if (alternatives.isEmpty()) "Buscar servidores novamente" else "Escolher outro servidor"
            isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                if (alternatives.isEmpty()) findFreeVpnGateServers() else showServerPicker(alternatives)
            }
        }
        panel.addView(choose, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val close = Button(this).apply {
            text = "Fechar"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        dialog.show()
    }

    private fun selectOpenVpnServer(server: PublicServer) {
        lastSelectedServerIp = server.ip
        statusText.text = "Validando o perfil com o núcleo OpenVPN 3…"
        profileText.text = "${server.country} • ${server.ip} • verificando configuração antes de conectar."

        executor.execute {
            val validation = OpenVpnNative.validateProfileOrMessage(server.ovpn)
            runOnUiThread {
                if (!validation.startsWith("OK:")) {
                    statusText.text = validation
                    profileText.text = "Perfil não iniciado. Verifique a configuração ou tente outro servidor."
                    AlertDialog.Builder(this)
                        .setTitle("Não foi possível validar o perfil")
                        .setMessage(validation)
                        .setPositiveButton("Entendi", null)
                        .show()
                    return@runOnUiThread
                }

                openVpnProfileAfterPermission = server.ovpn
                profileText.text = "${server.country} • ${server.ip} • perfil validado pelo OpenVPN 3 Core."
                AlertDialog.Builder(this)
                    .setTitle("Autorizar o túnel VPN")
                    .setMessage("O Android vai pedir permissão para o LivreNet criar uma conexão VPN e encaminhar o tráfego de rede pelo servidor selecionado. O app não consegue ler senhas do aparelho nem conceder essa autorização sozinho.")
                    .setNegativeButton("Cancelar") { _, _ ->
                        openVpnProfileAfterPermission = null
                        statusText.text = "Conexão cancelada. Nenhuma VPN foi iniciada."
                    }
                    .setPositiveButton("Continuar") { _, _ ->
                        statusText.text = "Solicitando autorização oficial do Android…"
                        val permissionIntent = VpnService.prepare(this)
                        if (permissionIntent == null) {
                            openVpnProfileAfterPermission = null
                            statusText.text = "Iniciando o túnel OpenVPN dentro do LivreNet…"
                            OpenVpnVpnService.connect(this, server.ovpn)
                        } else {
                            vpnPermission.launch(permissionIntent)
                        }
                    }
                    .show()
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
