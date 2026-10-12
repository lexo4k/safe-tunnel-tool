package com.livrenet.android

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

/**
 * SSH dynamic port forwarding. This creates a local SOCKS5 listener only;
 * Android-wide TUN forwarding requires a separate tun2socks engine.
 */
class SshSocksProxy {
    private var session: Session? = null
    val localPort: Int = 10808

    @Synchronized
    fun connect(host: String, port: Int, username: String, password: String, knownHostsPath: String, tlsWrapped: Boolean = false) {
        close()
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsPath)
        val newSession = jsch.getSession(username, host, port)
        newSession.setPassword(password)
        val options = java.util.Properties().apply {
            setProperty("StrictHostKeyChecking", "yes")
            setProperty("PreferredAuthentications", "password,keyboard-interactive")
            setProperty("ConnectTimeout", "12000")
            setProperty("ServerAliveInterval", "15000")
            setProperty("ServerAliveCountMax", "3")
        }
        newSession.setConfig(options)
        if (tlsWrapped) newSession.setProxy(TlsSshProxy())
        newSession.connect(15000)
        try {
            newSession.setPortForwardingD(localPort)
            session = newSession
        } catch (error: Exception) {
            runCatching { newSession.disconnect() }
            throw error
        }
    }

    @Synchronized
    fun close() {
        val old = session
        session = null
        runCatching { old?.delPortForwardingL(localPort) }
        runCatching { old?.disconnect() }
    }

    fun isConnected(): Boolean = session?.isConnected == true
}
