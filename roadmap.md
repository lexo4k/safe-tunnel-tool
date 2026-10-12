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
- [ ] Create a separate Android Gradle/Kotlin application module
- [ ] Integrate the official WireGuard Android tunnel implementation
- [ ] Add Android VPN consent flow using `VpnService.prepare()`
- [ ] Connect UI to actual native tunnel states; never simulate connected status
- [ ] Test using an authorized, reachable VPN server and valid profile on a physical Android device
- [ ] Verify real traffic reachability, disconnect, network changes, permission denial, and secret handling

## Important
The current repository is a web app, not a native VPN client. A real tunnel requires the Android module plus an authorized server/profile and underlying internet access. The UI must not claim internet access is free or unlimited, and must not bypass carrier billing or authentication.
