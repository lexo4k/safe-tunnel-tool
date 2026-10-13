package com.livrenet.android

/**
 * JNI facade for hev-socks5-tunnel.
 *
 * The native library registers these instance methods from JNI_OnLoad.
 * The TUN descriptor must stay valid until TProxyStopService returns.
 */
class HevTunnelEngine {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    external fun TProxyStartService(configPath: String, tunFd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray
}
