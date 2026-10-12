package com.livrenet.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Full-device TCP tunnel: Android TUN -> hev-socks5-tunnel -> local SOCKS5 -> SSH.
 * SSH server sockets are protected before connect to prevent routing them back into the TUN.
 * SSH direct-tcpip does not carry UDP; this service must never report UDP as tunneled.
 */
class SshVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var tunFd: Int = -1
    private var proxy: SshSocksProxy? = null
    private var engine: HevTunnelEngine? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparando túnel SSH"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                generation++
                worker.execute { stopTunnel(); stopSelf() }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 22)
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                val knownHosts = intent.getStringExtra(EXTRA_KNOWN_HOSTS).orEmpty()
                val tls = intent.getBooleanExtra(EXTRA_TLS, false)
                if (host.isBlank() || username.isBlank() || password.isBlank() ||
                    port !in 1..65535 || !File(knownHosts).isFile) {
                    publish("[ERROR] Dados SSH inválidos ou known_hosts ausente.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val id = ++generation
                worker.execute {
                    stopTunnel()
                    publish("[CONNECTING] Autenticando SSH e validando a chave do servidor…")
                    try {
                        val ssh = SshSocksProxy()
                        ssh.connect(
                            host, port, username, password, knownHosts, tlsWrapped = tls,
                            protectSocket = { socket -> protect(socket) }
                        )
                        if (id != generation) {
                            ssh.close()
                            return@execute
                        }
                        proxy = ssh
                        val builder = Builder()
                            .setSession("Lúmen VPN • SSH TCP")
                            .setMtu(1500)
                            .addAddress("198.18.0.1", 15)
                            .addRoute("0.0.0.0", 0)
                            .addDnsServer("1.1.1.1")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            builder.addAddress("fd00:1::1", 126)
                            builder.addRoute("::", 0)
                        }
                        val pfd = builder.establish()
                            ?: throw IllegalStateException("Android não permitiu criar a interface VPN.")
                        tun = pfd
                        tunFd = pfd.detachFd()
                        val configFile = File(filesDir, "hev-ssh-tunnel.yml")
                        configFile.writeText(
                            "tunnel:\n" +
                            "  name: tun0\n" +
                            "  mtu: 1500\n" +
                            "  ipv4: 198.18.0.1\n" +
                            "  ipv6: 'fd00:1::1'\n" +
                            "  icmp: 'off'\n" +
                            "socks5:\n" +
                            "  address: 127.0.0.1\n" +
                            "  port: ${ssh.localPort}\n" +
                            "  udp: 'udp'\n" +
                            "misc:\n" +
                            "  connect-timeout: 10000\n" +
                            "  log-level: info\n" +
                            "  log-file: null\n"
                        )
                        val native = HevTunnelEngine()
                        engine = native
                        if (!native.TProxyStartService(configFile.absolutePath, tunFd)) {
                            throw IllegalStateException("O motor TUN/SOCKS não iniciou. O SSH autenticou, mas o encaminhamento não foi ativado.")
                        }
                        if (id != generation) {
                            stopTunnel()
                            return@execute
                        }
                        running = true
                        publish("[CONNECTED] Túnel SSH TCP ativo. UDP/QUIC não é transportado pelo SSH direct-tcpip; DNS e apps que exigem UDP podem falhar.")
                    } catch (error: Throwable) {
                        stopTunnel()
                        publish("[ERROR] Falha no túnel SSH: ${error.message ?: error.javaClass.simpleName}")
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopTunnel() {
        running = false
        runCatching { engine?.TProxyStopService() }
        engine = null
        if (tunFd >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            tunFd = -1
        }
        runCatching { tun?.close() }
        tun = null
        runCatching { proxy?.close() }
        proxy = null
    }

    override fun onRevoke() {
        generation++
        worker.execute { stopTunnel(); stopSelf() }
        super.onRevoke()
    }

    override fun onDestroy() {
        generation++
        runCatching { stopTunnel() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun publish(message: String) {
        val text = message.removePrefix("[CONNECTED] ").removePrefix("[CONNECTING] ").removePrefix("[ERROR] ")
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text.take(160)))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lúmen VPN • SSH", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 42, Intent(this, SshVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Lúmen VPN • SSH")
            .setContentText(text)
            .setOngoing(running || !text.contains("desconect", true))
            .addAction(Notification.Action.Builder(null, "Desconectar", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.livrenet.android.sshvpn.START"
        const val ACTION_STOP = "com.livrenet.android.sshvpn.STOP"
        const val ACTION_STATUS = "com.livrenet.android.sshvpn.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_KNOWN_HOSTS = "known_hosts"
        const val EXTRA_TLS = "tls"
        private const val CHANNEL_ID = "lumen_ssh_vpn"
        private const val NOTIFICATION_ID = 4207
    }
}
