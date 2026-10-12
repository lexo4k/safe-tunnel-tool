package com.livrenet.android

/**
 * JNI boundary for the source-built OpenVPN 3 Core.
 *
 * The native validation method checks .ovpn profile syntax only. A working
 * tunnel additionally requires the Android TunBuilder/VpnService callbacks,
 * which are deliberately not reported as connected by this bridge.
 */
object OpenVpnNative {
    private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("livrenet_openvpn")
            loaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun coreInfoOrNull(): String? =
        if (load()) runCatching { nativeCoreInfo() }.getOrNull() else null

    fun validateProfileOrMessage(profile: String): String =
        if (load()) runCatching { nativeValidateProfile(profile) }
            .getOrElse { "Falha ao validar perfil no núcleo OpenVPN: ${it.message}" }
        else "Núcleo OpenVPN nativo ainda não foi empacotado neste APK."

    @JvmStatic private external fun nativeCoreInfo(): String
    @JvmStatic external fun nativeValidateProfile(profile: String): String
}
