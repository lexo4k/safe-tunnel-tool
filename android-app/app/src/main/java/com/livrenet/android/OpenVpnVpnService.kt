package com.livrenet.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.net.InetAddress
import java.util.concurrent.Executors

class OpenVpnVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var reconnectRequested = false
    @Volatile private var connectionGeneration = 0
    private var tunnelBuilder: Builder? = null
    private var detachedTunFd: Int = -1
    private var notificationText = "Preparando o túnel OpenVPN"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notificationText))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                connectionGeneration++
                reconnectRequested = false
                runCatching { nativeStop() }
                notificationText = "Desconectando OpenVPN"
                updateNotification()
                publishStatus("OpenVPN desconectado por solicitação do usuário.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val profile = intent.getStringExtra(EXTRA_PROFILE)
                if (profile.isNullOrBlank()) {
                    publishStatus("ERRO: perfil OpenVPN vazio.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val generation = ++connectionGeneration
                reconnectRequested = true
                runCatching { nativeStop() }
                notificationText = "Conectando ao servidor OpenVPN…"
                updateNotification()
                publishStatus("Conectando ao servidor OpenVPN…")
                worker.execute {
                    var retry = 0
                    var finalMessage = "OpenVPN desconectado."
                    while (reconnectRequested && generation == connectionGeneration) {
                        finalMessage = try {
                            nativeConnect(profile, this)
                        } catch (error: Throwable) {
                            "Falha no motor OpenVPN: ${error.message ?: error.javaClass.simpleName}"
                        }

                        if (!reconnectRequested || generation != connectionGeneration) break
                        if (isNonRetryableFailure(finalMessage) || retry >= MAX_RECONNECTS) {
                            publishStatus(finalMessage)
                            reconnectRequested = false
                            break
                        }

                        val waitSeconds = RETRY_DELAYS_SECONDS[retry.coerceAtMost(RETRY_DELAYS_SECONDS.lastIndex)]
                        retry++
                        publishStatus("Conexão interrompida. Nova tentativa automática em ${waitSeconds}s ($retry/$MAX_RECONNECTS).")
                        try {
                            Thread.sleep(waitSeconds * 1000L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            reconnectRequested = false
                        }
                    }
                    if (generation == connectionGeneration && !reconnectRequested) {
                        publishStatus("OpenVPN desconectado ou tentativas automáticas encerradas.")
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun isNonRetryableFailure(message: String): Boolean {
        val lower = message.lowercase()
        return listOf(
            "unsupportedcertversion", "invalid peer certificate", "certificate verify failed",
            "certificate verification failed", "tls handshake", "tls error", "handshake failed",
            "no shared cipher", "authentication failed", "auth_failed", "invalid client certificate",
            "private key", "unsupported protocol", "erro de perfil", "perfil vazio"
        ).any { lower.contains(it) }
    }

    override fun onDestroy() {
        connectionGeneration++
        reconnectRequested = false
        runCatching { nativeStop() }
        worker.shutdownNow()
        tunnelBuilder = null
        super.onDestroy()
    }

    fun nativeStatus(message: String) {
        publishStatus(message)
    }

    fun nativeProtectSocket(fd: Int): Boolean = protect(fd)

    fun nativeNewTunnel(): Boolean {
        return try {
            tunnelBuilder = Builder().setSession("LivreNet • OpenVPN")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeAddAddress(address: String, prefix: Int, gateway: String, ipv6: Boolean, net30: Boolean): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            builder.addAddress(address, prefix)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeRerouteGateway(ipv4: Boolean, ipv6: Boolean, flags: Int): Boolean {
        return try {
            val builder = tunnelBuilder ?: return false
            if (ipv4) builder.addRoute("0.0.0.0", 0)
            if (ipv6) builder.addRoute("::", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeAddRoute(address: String, prefix: Int, metric: Int, ipv6: Boolean): Boolean {
        return try {
            (tunnelBuilder ?: return false).addRoute(address, prefix)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeExcludeRoute(address: String, prefix: Int, metric: Int, ipv6: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (tunnelBuilder ?: return false).excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeAddDnsServer(address: String): Boolean {
        return try {
            (tunnelBuilder ?: return false).addDnsServer(address)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeSetMtu(mtu: Int): Boolean {
        return try {
            if (mtu > 0) (tunnelBuilder ?: return false).setMtu(mtu)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeSetSessionName(name: String): Boolean {
        return try {
            (tunnelBuilder ?: return false).setSession(name)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun nativeEstablish(): Int {
        return try {
            val pfd: ParcelFileDescriptor = (tunnelBuilder ?: return -1).establish() ?: return -1
            detachedTunFd = pfd.detachFd()
            detachedTunFd
        } catch (_: Exception) {
            -1
        }
    }

    fun nativeTeardown(disconnect: Boolean) {
        tunnelBuilder = null
        detachedTunFd = -1
    }

    private fun publishStatus(message: String) {
        notificationText = message.take(180)
        updateNotification()
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, message)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LivreNet VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, OpenVpnVpnService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            10,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("LivreNet • OpenVPN")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desconectar", stopPendingIntent).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(notificationText))
    }

    companion object {
        const val ACTION_CONNECT = "com.livrenet.android.OPENVPN_CONNECT"
        const val ACTION_STOP = "com.livrenet.android.OPENVPN_STOP"
        const val ACTION_STATUS = "com.livrenet.android.OPENVPN_STATUS"
        const val EXTRA_PROFILE = "openvpn_profile"
        const val EXTRA_STATUS = "openvpn_status"

        private const val CHANNEL_ID = "livrenet_openvpn"
        private const val NOTIFICATION_ID = 4172
        private const val MAX_RECONNECTS = 3
        private val RETRY_DELAYS_SECONDS = intArrayOf(2, 5, 10)

        @JvmStatic external fun nativeConnect(profile: String, service: OpenVpnVpnService): String
        @JvmStatic external fun nativeStop()

        fun connect(context: Context, profile: String) {
            val intent = Intent(context, OpenVpnVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE, profile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, OpenVpnVpnService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
