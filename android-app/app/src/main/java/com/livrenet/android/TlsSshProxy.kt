package com.livrenet.android

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Wraps an SSH protocol stream in a TLS connection.
 *
 * The remote endpoint must explicitly support TLS-wrapped SSH. This is not
 * compatible with a normal SSH daemon unless a TLS wrapper is configured there.
 * The platform trust store and hostname verification remain enabled.
 */
class TlsSshProxy : Proxy {
    @Volatile private var tlsSocket: SSLSocket? = null

    override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) {
        require(!host.isNullOrBlank()) { "Host TLS/SSH vazio." }
        require(port in 1..65535) { "Porta TLS/SSH inválida." }
        close()

        val socket = (SSLSocketFactory.getDefault().createSocket() as SSLSocket)
        try {
            socket.connect(java.net.InetSocketAddress(host, port), timeout.coerceAtLeast(1000))
            socket.soTimeout = timeout.coerceAtLeast(1000)
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(javax.net.ssl.SNIHostName(host))
            }
            socket.startHandshake()
            tlsSocket = socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw IllegalStateException("Falha no TLS ou certificado do servidor: ${error.message}", error)
        }
    }

    override fun getInputStream(): InputStream =
        requireNotNull(tlsSocket) { "Transporte TLS não conectado." }.inputStream

    override fun getOutputStream(): OutputStream =
        requireNotNull(tlsSocket) { "Transporte TLS não conectado." }.outputStream

    override fun getSocket(): Socket? = tlsSocket

    override fun close() {
        val old = tlsSocket
        tlsSocket = null
        runCatching { old?.close() }
    }
}
