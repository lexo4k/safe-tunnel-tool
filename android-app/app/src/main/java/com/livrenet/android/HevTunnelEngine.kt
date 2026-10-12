package com.livrenet.android

/**
 * JNI facade for hev-socks5-tunnel.
 *
 * The native library registers these methods from JNI_OnLoad. The descriptor
 * must come from VpnService.Builder.establish() and remain owned by the
 * service until TProxyStopService has returned.
 */
object HevTunnelEngine {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic external fun TProxyStartService(configPath: String, tunFd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray
}
