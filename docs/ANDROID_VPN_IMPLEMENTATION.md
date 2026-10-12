# Android VPN implementation plan

## Current state

LivreNet in this repository is a React/Vite web application. A web page cannot register Android's `VpnService`, create a system VPN interface, or carry device traffic through a tunnel. The existing web dashboard must continue to say **not connected** unless a native VPN client reports a real state. Browser `navigator.onLine` only indicates a network interface may be available; it does not prove internet reachability.

## Intended Android architecture

- Keep this web dashboard as the management/UI prototype.
- Add a separate native Android app/module (Kotlin, Android Studio/Gradle) for actual tunnel operation.
- Prefer the official WireGuard Android tunnel implementation/library for WireGuard profiles rather than implementing cryptography or the WireGuard protocol from scratch.
- Use Android's `VpnService.prepare(activity)` flow to request the system's explicit VPN permission. Never attempt to bypass this prompt.
- Start/stop the tunnel through the supported native API; report states from the tunnel backend, not from UI button clicks.
- Keep private keys and profile contents on-device. Do not send them to analytics, logs, a web backend, or Git.
- Validate imported profile syntax locally and show actionable errors without echoing secrets.
- Make clear that a tunnel needs an authorized, reachable VPN endpoint and working underlying Wi-Fi/mobile data. The app cannot create free mobile data or bypass carrier billing, authentication, or restrictions.
- Do not ship a fake server list, pretend latency/throughput, or synthetic connected state as real.

## Connection lifecycle

1. User selects **Importar perfil** and chooses a WireGuard configuration through Android's document picker, or enters configuration supplied by their VPN administrator/provider.
2. App validates the profile locally and displays only non-secret metadata such as profile name and endpoint host.
3. User taps **Conectar**.
4. App calls `VpnService.prepare()`. If Android returns an intent, launch it and wait for the user's decision.
5. After permission is granted, pass the profile to the WireGuard tunnel backend and start the tunnel.
6. Reflect the backend's actual state: starting, connected, reconnecting, stopped, or error. A successful API call alone is not proof that internet traffic works.
7. Provide a separate optional reachability check against a fixed, documented health endpoint controlled by the app operator. Do not probe arbitrary endpoints from imported profiles.
8. On **Desconectar**, stop the native tunnel and update the UI from the backend state.

## Acceptance checks before calling this functional

- Android build succeeds and the app installs on a physical Android device.
- Android displays the system VPN consent prompt on first connection.
- A valid profile for an authorized, reachable server connects successfully.
- Invalid profiles and unreachable endpoints show errors and never produce a connected badge.
- A traffic/reachability test succeeds while connected and fails gracefully when the server is unavailable.
- Disconnect stops the tunnel and restores normal routing.
- Reboot, app backgrounding, network switching, and permission denial are handled.
- No secret keys appear in logs, crash reports, analytics, source control, or web requests.
- UI distinguishes **VPN tunnel connected** from **internet reachability verified**.

## Current limitation

This repository does not currently contain an Android Gradle project, native `VpnService`, WireGuard backend, or a VPN server endpoint. The web-only changes cannot provide a real device-wide tunnel by themselves. These items require a native Android module and an authorized server/profile; they cannot be completed by changing React buttons alone.
