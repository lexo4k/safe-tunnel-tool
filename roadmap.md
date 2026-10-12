# LivreNet roadmap

## Web prototype
- [x] Responsive dashboard and shared navigation
- [x] Demo server list clearly marked as not connected
- [x] Local-only profile summary and superficial validation
- [x] Connectivity checklist with web/native limitations
- [x] Responsible low-cost internet guide
- [ ] Run automated tests and verify mobile/desktop layouts

## Real Android VPN
- [x] Document native integration architecture and acceptance criteria in [docs/ANDROID_VPN_IMPLEMENTATION.md](docs/ANDROID_VPN_IMPLEMENTATION.md)
- [x] Create a separate Android Gradle/Kotlin application module
- [x] Integrate the official WireGuard Android tunnel implementation
- [x] Add Android VPN consent flow using `VpnService.prepare()`
- [x] Add native connect/disconnect controls backed by the WireGuard API (device/server validation still pending)
- [ ] Provision/authorize a real VPN gateway and test using its valid profile on a physical Android device
- [ ] Verify real traffic reachability, disconnect, network changes, permission denial, and secret handling

## Important
The repository now contains both the web dashboard and an initial native Android client. A verified working tunnel still requires an authorized, reachable VPN server/profile and testing on a physical Android device. No LivreNet-operated VPN servers are provisioned yet. The UI must not claim internet access is free or unlimited, and must not bypass carrier billing or authentication.
