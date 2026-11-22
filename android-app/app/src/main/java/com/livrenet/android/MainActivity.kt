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
    @Volatile private var openVpnConnected = false
    @Volatile private var openVpnConnecting = false
    @Volatile private var openVpnServiceStarted = false
    @Volatile private var connectAttemptId = 0
    @Volatile private var selectedOpenVpnServer: PublicServer? = null

    private val openVpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(OpenVpnVpnService.EXTRA_STATUS) ?: return
            val lower = message.lowercase()
            val upper = message.uppercase()
            val connectionEstablished =
                (upper.contains("CONNECTED") || upper.contains("CONNECTION_ESTABLISHED") || upper.contains("TUNNEL UP")) &&
                    listOf("DISCONNECTED", "NOT CONNECTED", "CONNECTING", "UNCONNECTED").none { upper.contains(it) }
            val connectionEnded = listOf(
                "DISCONNECTED", "DESCONECTADO", "DESCONECTANDO", "CONNECTION FAILED",
                "CONNECTION CANCELED", "CONNECTION CANCELLED", "AUTHENTICATION FAILED",
                "TLS ERROR", "TUNNEL CLOSED", "SOCKET CLOSED", "NETWORK UNREACHABLE",
                "CONNECTION TIMEOUT", "TIMEOUT", "STOPPED", "CANCELADO", "CANCELANDO",
                "ERRO:", "FALHA"
            ).any { upper.contains(it) }

            if (connectionEstablished) {
                openVpnConnected = true
                openVpnConnecting = false
                runOnUiThread {
                    statusText.setTextColor(android.graphics.Color.rgb(74, 222, 128))
                    statusText.text = "✓ VPN conectada. O núcleo confirmou a conexão."
                    internetText.setTextColor(android.graphics.Color.rgb(74, 222, 128))
                    internetText.text = "Túnel VPN conectado"
                    refreshConnectButton()
                }
                return
            }
            if (connectionEnded) {
                openVpnConnected = false
                openVpnConnecting = false
                openVpnServiceStarted = false
            } else if (upper.contains("CONNECTING") || upper.contains("RETRY") || lower.contains("iniciando")) {
                openVpnConnecting = true
                if (upper.contains("RETRY")) openVpnServiceStarted = true
            }
            if (openVpnConnected && !connectionEnded) return
            runOnUiThread {
                statusText.setTextColor(
                    if (connectionEnded) android.graphics.Color.rgb(248, 113, 113)
                    else android.graphics.Color.rgb(157, 178, 199)
                )
                statusText.text = message
                if (connectionEnded) {
                    internetText.setTextColor(android.graphics.Color.rgb(248, 113, 113))
                    internetText.text = "VPN desconectada ou com falha"
                }
                refreshConnectButton()
            }
            val tlsOrAuthFailure = listOf(
                "unsupportedcertversion", "invalid peer certificate", "certificate verify failed",
                "certificate verification failed", "tls handshake", "tls error",
                "handshake failed", "no shared cipher", "peer certificate", "authentication failed",
                "auth_failed", "private key", "unsupported protocol"
            ).any { lower.contains(it) }
            if (tlsOrAuthFailure && !tlsDialogShowing) {
                runOnUiThread {
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
                if (connected) statusText.text = "Túnel WireGuard conectado."
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
            connectAttemptId++
            stream.use { config = Config.parse(it) }
            selectedOpenVpnServer = null
            profileText.text = "Perfil WireGuard carregado na memória do app."
            statusText.text = "Perfil pronto. Toque em Conectar."
            refreshConnectButton()
        } catch (error: Exception) {
            config = null
            selectedOpenVpnServer = null
            profileText.text = "Nenhum perfil WireGuard válido carregado."
            statusText.text = "Não foi possível ler esse perfil: ${error.message ?: "formato inválido"}"
            refreshConnectButton()
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pendingOpenVpnProfile = openVpnProfileAfterPermission
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingOpenVpnProfile != null) {
                openVpnProfileAfterPermission = null
                openVpnConnecting = true
                openVpnServiceStarted = true
                refreshConnectButton()
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
            connectAttemptId++
            openVpnConnecting = false
            openVpnServiceStarted = false
            statusText.text = "Permissão VPN não concedida. Nenhum túnel foi iniciado."
            refreshConnectButton()
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
        val ink = android.graphics.Color.rgb(6, 13, 28)
        val panel = android.graphics.Color.rgb(15, 27, 48)
        val cyan = android.graphics.Color.rgb(0, 218, 230)
        val white = android.graphics.Color.rgb(241, 247, 255)
        val muted = android.graphics.Color.rgb(143, 162, 190)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panel); cornerRadius = dp(18).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(35, 55, 82))
            }
        }
        fun actionButton(textValue: String, primary: Boolean = false, action: () -> Unit) =
            Button(this).apply {
                text = textValue; isAllCaps = false; textSize = 15f
                setTextColor(if (primary) ink else white)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (primary) cyan else android.graphics.Color.rgb(27, 43, 68))
                    cornerRadius = dp(14).toFloat()
                    if (!primary) setStroke(dp(1), android.graphics.Color.rgb(55, 78, 108))
                }
                minHeight = dp(52); setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { action() }
            }
        val scroll = android.widget.ScrollView(this).apply { setBackgroundColor(ink); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.livrenet_logo)
            contentDescription = "LivreNet"
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(12) }
        })
        val brandCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCopy.addView(label("LIVRENET", 25f, white, true))
        brandCopy.addView(label("VPN • CONEXÃO PRIVADA", 10f, cyan, true))
        brand.addView(brandCopy)
        root.addView(brand)
        root.addView(label("Sua conexão, em um toque.", 23f, white, true).apply {
            setPadding(0, dp(24), 0, dp(5))
        })
        root.addView(label("Escolha um servidor e conecte pelo próprio LivreNet.", 13f, muted))
        val statusCard = card().apply {
            addView(label("STATUS DA CONEXÃO", 10f, cyan, true))
            internetText = label("Verificando rede…", 16f, white, true).apply { setPadding(0, dp(8), 0, dp(3)) }
            addView(internetText)
            statusText = label("VPN desconectada", 12f, muted)
            addView(statusText)
            connectButton = actionButton("CONECTAR", true) {
                when {
                    openVpnConnected || openVpnConnecting -> {
                        connectAttemptId++
                        val stop = openVpnServiceStarted || openVpnConnected
                        openVpnConnected = false; openVpnConnecting = false; openVpnServiceStarted = false
                        if (stop) OpenVpnVpnService.disconnect(this@MainActivity)
                        statusText.text = "Encerrando conexão…"; refreshConnectButton()
                    }
                    connected -> disconnectTunnel()
                    selectedOpenVpnServer != null -> selectOpenVpnServer(selectedOpenVpnServer!!)
                    config != null -> requestVpnPermission()
                    else -> {
                        statusText.text = "Selecione um servidor OpenVPN ou importe um perfil WireGuard."
                        findFreeVpnGateServers()
                    }
                }
            }
            addView(connectButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(16) })
        }
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        root.addView(label("SERVIDORES", 11f, cyan, true).apply { setPadding(2, dp(24), 0, dp(9)) })
        val servers = card()
        servers.addView(label("Servidores disponíveis", 17f, white, true))
        servers.addView(label("Lista pública de perfis OpenVPN. Servidores de terceiros podem ficar indisponíveis; o LivreNet não opera esses servidores.", 12f, muted).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        freeVpnButton = actionButton("BUSCAR SERVIDORES", true) { findFreeVpnGateServers() }
        servers.addView(freeVpnButton, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(servers)
        root.addView(label("MODO DE CONEXÃO", 11f, cyan, true).apply { setPadding(2, dp(24), 0, dp(9)) })
        val modes = card()
        fun mode(name: String, detail: String, available: Boolean, selected: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(20, 37, 61))
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), if (selected) cyan else android.graphics.Color.rgb(43, 64, 91))
                }
            }
            row.addView(label(name, 14f, white, true))
            row.addView(label(detail, 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
            modes.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        mode("OpenVPN", "Motor integrado e funcional com perfil/servidor compatível.", true, true)
        mode("WireGuard", "Motor integrado; requer importar um perfil .conf válido.", true)
        mode("SSH Direct", "Motor SSH ainda não integrado nesta versão.", false)
        mode("SSH + Proxy", "Motor SSH/Proxy ainda não integrado nesta versão.", false)
        mode("SSH + SSL", "Transporte TLS/SSH ainda não integrado nesta versão.", false)
        mode("SSH + SSL Proxy", "Transporte TLS/SSH/Proxy ainda não integrado nesta versão.", false)
        root.addView(modes)
        val profile = card().apply {
            addView(label("PERFIL WIREGUARD", 11f, cyan, true))
            profileText = label("Nenhum perfil carregado.", 12f, muted).apply { setPadding(0, dp(7), 0, dp(10)) }
            addView(profileText)
            addView(actionButton("IMPORTAR PERFIL .CONF") {
                profilePicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
        root.addView(profile, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(actionButton("DIAGNÓSTICO DE CONEXÃO") { refreshInternetStatus() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        root.addView(label("A VPN precisa de uma conexão Wi-Fi ou móvel ativa e de um servidor autorizado. O LivreNet não cria internet nem fornece acesso gratuito à rede móvel.", 11f, muted).apply {
            setPadding(dp(3), dp(18), dp(3), 0)
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
                            val fields = parseCsvRecord(line)
                            if (fields.size < 15) return@mapNotNull null
                            val ip = fields[1].trim()
                            val speed = fields[4].trim().toLongOrNull() ?: 0L
                            val country = fields[5].trim().ifBlank { "País desconhecido" }
                            val encodedConfig = fields[14].trim()
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

    /**
     * Parses one CSV record while respecting quoted fields and escaped double quotes.
     * VPN Gate metadata can contain commas inside quoted text, so String.split(",")
     * can shift the OpenVPN profile column and produce invalid profiles.
     */
    private fun parseCsvRecord(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(ch)
            }
            i++
        }
        fields.add(field.toString())
        return fields
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
            card.contentDescription = "Selecionar servidor ${server.country}, IP ${server.ip}"
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener {
                selectedOpenVpnServer = server
                serverDialog?.dismiss()
                profileText.text = "Selecionado: ${server.country} • ${server.ip}. Use o botão Conectar na tela principal."
                statusText.text = "Servidor selecionado. Toque em Conectar para iniciar o túnel."
                refreshConnectButton()
            }
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
        statusText.text = "${servers.size} servidores OpenVPN encontrados. Toque em um cartão para selecionar e depois use Conectar."
        profileText.text = selectedOpenVpnServer?.let { "Selecionado: ${it.country} • ${it.ip}" }
            ?: "Nenhum servidor selecionado. Toque em um cartão da lista."
        refreshConnectButton()
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
        val attemptId = ++connectAttemptId
        lastSelectedServerIp = server.ip
        openVpnConnecting = true
        refreshConnectButton()
        statusText.text = "Validando o perfil com o núcleo OpenVPN 3…"
        profileText.text = "${server.country} • ${server.ip} • verificando configuração antes de conectar."

        executor.execute {
            val validation = OpenVpnNative.validateProfileOrMessage(server.ovpn)
            runOnUiThread {
                if (attemptId != connectAttemptId) return@runOnUiThread
                if (!validation.startsWith("OK:")) {
                    openVpnConnecting = false
                    refreshConnectButton()
                    statusText.text = validation
                    profileText.text = "Perfil não iniciado. Verifique a configuração ou tente outro servidor."
                    AlertDialog.Builder(this)
                        .setTitle("Não foi possível validar o perfil")
                        .setMessage(validation)
                        .setPositiveButton("Entendi", null)
                        .show()
                    return@runOnUiThread
                }

                openVpnConnected = false
                openVpnConnecting = true
                refreshConnectButton()
                statusText.setTextColor(android.graphics.Color.rgb(157, 178, 199))
                statusText.text = "Perfil válido. Solicitando a permissão VPN do Android…"
                profileText.text = "${server.country} • ${server.ip} • perfil validado pelo OpenVPN 3 Core."
                openVpnProfileAfterPermission = server.ovpn
                val permissionIntent = VpnService.prepare(this)
                if (permissionIntent == null) {
                    openVpnProfileAfterPermission = null
                    statusText.text = "Permissão já concedida. Iniciando o túnel OpenVPN…"
                    openVpnConnecting = true
                    openVpnServiceStarted = true
                    refreshConnectButton()
                    OpenVpnVpnService.connect(this, server.ovpn)
                } else {
                    vpnPermission.launch(permissionIntent)
                }
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

    private fun refreshConnectButton() {
        if (!::connectButton.isInitialized) return
        val active = connected || openVpnConnected
        connectButton.text = when {
            active -> "Desconectar"
            openVpnConnecting -> "Cancelar conexão"
            else -> "Conectar"
        }
        connectButton.isEnabled = active || openVpnConnecting || selectedOpenVpnServer != null || config != null
        connectButton.contentDescription = when {
            active -> "Desconectar a VPN"
            openVpnConnecting -> "Cancelar tentativa de conexão VPN"
            else -> selectedOpenVpnServer?.let { "Conectar ao servidor ${it.country}" } ?: "Conectar à VPN"
        }
    }

    private fun renderState() {
        if (!::connectButton.isInitialized) return
        refreshConnectButton()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
