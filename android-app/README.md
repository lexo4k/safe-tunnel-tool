# LivreNet Android

Native Android companion app for the LivreNet project.

## Current functionality (version 0.4.0)

- Native Portuguese interface with a dark navy / turquoise visual identity, responsive scrolling layout, and globe-inspired LivreNet branding.
- OpenVPN is the requested primary flow: VPN Gate server discovery and direct handoff of a selected `.ovpn` profile to a compatible external client, without Android's generic share sheet. The OpenVPN core is not embedded yet.
- Android VPN consent prompt via `VpnService.prepare()`.
- Checks the WireGuard backend state after a connect request instead of treating the request alone as proof that the tunnel is active.
- WireGuard remains an integrated alternative for valid `.conf` profiles; it is not presented as the primary workflow.
- Public server discovery through the VPN Gate public API, with country/IP labels and advertised speed ordering.
- Discovers public VPN Gate profiles and attempts to open a selected `.ovpn` profile directly in a known compatible OpenVPN client, without opening Android's share sheet.
- Builds a debug APK through GitHub Actions.

## Protocol support and remaining implementation work

- **OpenVPN:** requested primary workflow, but its core is not embedded yet. Selecting a VPN Gate server writes a temporary `.ovpn` profile and attempts to open it directly in a known compatible external client. The user must confirm import/connection in that client; LivreNet itself does not establish the OpenVPN tunnel.
- **WireGuard:** integrated alternative, using a user-supplied valid `.conf` profile. The app checks the backend state after requesting connection; successful tunnel activation still does not guarantee that the remote server has working routes or internet access.
- **SSH Direct, SSH + Proxy, SSH + SSL, SSH + SSL Proxy:** not implemented yet. These require real SSH transport implementations, correct proxy/TLS handling, and an authorized reachable SSH gateway. The interface deliberately does not claim these modes work.

To finish the requested multi-protocol experience, the next engineering tasks are integrating and testing an OpenVPN engine suitable for Android, then implementing or integrating maintained SSH transport components, adding server/profile configuration for authorized endpoints, and testing connect/disconnect, reconnects, DNS, routes, and traffic on real devices. No private server or credentials are available in this repository, so those cannot be fabricated.

## Important protocol limitation

The app does **not** yet run OpenVPN inside LivreNet. VPN Gate profiles use OpenVPN, while the embedded native tunnel backend currently supports WireGuard only. Selecting a VPN Gate server attempts to open its profile directly in an external compatible OpenVPN client, but the app cannot establish that tunnel internally until the OpenVPN core and Android TUN callbacks are integrated. The WireGuard connect button cannot connect an OpenVPN profile.

The VPN Gate servers are operated by public volunteers, may be offline or slow, and may change without notice. They are not operated by LivreNet. Do not use public VPN servers for sensitive activity; the server operator may be able to observe some connection metadata or unencrypted traffic. Review the provider's terms and privacy information.

## Server and profile requirements

This repository does not operate a VPN gateway and does not include private server credentials. A valid WireGuard profile must come from a server the user owns or is authorized to use. The app cannot create a working tunnel by inventing an address or cryptographic keys.

The app does not promise free or unlimited mobile data and does not bypass carrier plans or restrictions.

## Build

The workflow `.github/workflows/android-debug-apk.yml` runs `gradle assembleDebug` from this directory and uploads `app-debug.apk` as the `livrenet-android-debug` artifact. The debug APK is for testing and is not a Play Store release.

## Privacy and permissions

Android asks the user to authorize VPN access when a tunnel is started. The VPN Gate profile is downloaded from the public service and, when a compatible external client is installed, opened through a temporary cache file shared with that client using a read-only content URI. Do not commit VPN profiles or private keys to GitHub.
