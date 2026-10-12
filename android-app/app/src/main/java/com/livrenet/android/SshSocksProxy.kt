package com.livrenet.android

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local SOCKS5 CONNECT proxy carried over authenticated SSH.
 *
 * This is TCP-only: JSch direct-tcpip channels do not provide SOCKS5 UDP ASSOCIATE.
 * A full-device tun2socks tunnel must not claim that UDP traffic is tunneled.
 */
class SshSocksProxy {
    @Volatile private var session: Session? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var workers: ExecutorService? = null
    @Volatile var localPort: Int = 0
        private set

    @Synchronized
    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        knownHostsPath: String,
        tlsWrapped: Boolean = false
    ) {
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
            val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            val pool = Executors.newCachedThreadPool()
            session = newSession
            listener = server
            workers = pool
            localPort = server.localPort
            Thread({ acceptClients(server, pool, newSession) }, "lumen-ssh-socks-accept").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            runCatching { newSession.disconnect() }
            throw error
        }
    }

    private fun acceptClients(server: ServerSocket, pool: ExecutorService, ssh: Session) {
        while (!server.isClosed && ssh.isConnected) {
            try {
                val client = server.accept()
                pool.execute {
                    handleClient(client, ssh)
                }
            } catch (_: Exception) {
                if (!server.isClosed) {
                    // Continue accepting after an individual transient accept error.
                }
            }
        }
    }

    private fun handleClient(client: Socket, ssh: Session) {
        client.use { socket ->
            socket.soTimeout = 30000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            try {
                if (input.readUnsignedByte() != 5) return
                val methods = input.readUnsignedByte()
                var noAuthSupported = false
                repeat(methods) { if (input.readUnsignedByte() == 0) noAuthSupported = true }
                if (!noAuthSupported) {
                    output.write(byteArrayOf(5, 0xFF.toByte()))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(5, 0))
                output.flush()

                if (input.readUnsignedByte() != 5) return
                val command = input.readUnsignedByte()
                input.readUnsignedByte() // reserved
                val addressType = input.readUnsignedByte()
                val destination = when (addressType) {
                    1 -> InetAddress.getByAddress(ByteArray(4) { input.readByte() }).hostAddress
                    3 -> {
                        val size = input.readUnsignedByte()
                        val bytes = ByteArray(size)
                        input.readFully(bytes)
                        String(bytes, StandardCharsets.UTF_8)
                    }
                    4 -> InetAddress.getByAddress(ByteArray(16) { input.readByte() }).hostAddress
                    else -> {
                        writeReply(output, 8)
                        return
                    }
                }
                val destinationPort = input.readUnsignedShort()
                if (command != 1) {
                    writeReply(output, 7) // command not supported (UDP ASSOCIATE is intentionally unsupported)
                    return
                }

                val channel = try {
                    (ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP).apply {
                        setHost(destination)
                        setPort(destinationPort)
                        connect(15000)
                    }
                } catch (_: Exception) {
                    writeReply(output, 5) // connection refused
                    return
                }

                socket.soTimeout = 0
                writeReply(output, 0)
                val sshInput = channel.inputStream
                val sshOutput = channel.outputStream
                val relayPool = workers
                if (relayPool == null) {
                    channel.disconnect()
                    return
                }
                relayPool.execute { copyStream(input, sshOutput, channel, socket) }
                relayPool.execute { copyStream(sshInput, output, channel, socket) }
                // The two relay tasks own the streams until either side closes.
                while (channel.isConnected && !socket.isClosed) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                channel.disconnect()
            } catch (_: EOFException) {
                // Client closed during SOCKS negotiation.
            } catch (_: Exception) {
                // Close this client only; the SSH session remains available.
            }
        }
    }

    private fun writeReply(output: DataOutputStream, reply: Int) {
        output.write(byteArrayOf(5, reply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream, channel: ChannelDirectTCPIP, socket: Socket) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { channel.disconnect() }
            runCatching { socket.close() }
        }
    }

    @Synchronized
    fun close() {
        val oldListener = listener
        listener = null
        runCatching { oldListener?.close() }
        workers?.shutdownNow()
        workers = null
        localPort = 0
        val oldSession = session
        session = null
        runCatching { oldSession?.disconnect() }
    }

    fun isConnected(): Boolean = session?.isConnected == true && listener?.isClosed == false
}