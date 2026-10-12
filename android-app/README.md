# LivreNet Android

Native Android companion app for the LivreNet project.

## Current functionality (version 0.3.0)

- Native Portuguese interface with a dark navy / turquoise visual identity, responsive scrolling layout, and globe-inspired LivreNet branding.
- Manual import and real connect/disconnect for WireGuard `.conf` profiles using the official WireGuard tunnel backend.
- Android VPN consent prompt via `VpnService.prepare()`.
- Public server discovery through the VPN Gate public API, with country/IP labels and advertised speed ordering.
- Downloads the selected VPN Gate OpenVPN configuration and shares it as an `.ovpn` profile with an installed compatible OpenVPN client.
- Builds a debug APK through GitHub Actions.

## Important protocol limitation

The app does **not** yet run OpenVPN inside LivreNet. VPN Gate profiles use OpenVPN, while the embedded native tunnel backend currently supports WireGuard only. For a VPN Gate profile, an external compatible OpenVPN client must be installed and selected to import the downloaded profile and connect. The in-app WireGuard connect button cannot connect an OpenVPN profile.

The VPN Gate servers are operated by public volunteers, may be offline or slow, and may change without notice. They are not operated by LivreNet. Do not use public VPN servers for sensitive activity; the server operator may be able to observe some connection metadata or unencrypted traffic. Review the provider's terms and privacy information.

## Server and profile requirements

This repository does not operate a VPN gateway and does not include private server credentials. A valid WireGuard profile must come from a server the user owns or is authorized to use. The app cannot create a working tunnel by inventing an address or cryptographic keys.

The app does not promise free or unlimited mobile data and does not bypass carrier plans or restrictions.

## Build

The workflow `.github/workflows/android-debug-apk.yml` runs `gradle assembleDebug` from this directory and uploads `app-debug.apk` as the `livrenet-android-debug` artifact. The debug APK is for testing and is not a Play Store release.

## Privacy and permissions

Android asks the user to authorize VPN access when a tunnel is started. The VPN Gate profile is downloaded from the public service and shared through Android's file-sharing mechanism; the profile is stored temporarily in the app cache. Do not commit VPN profiles or private keys to GitHub.
