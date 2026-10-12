# LivreNet Android

This directory is a separate native Android client for the existing LivreNet web project.

## What is implemented

- Native Android screen in Portuguese.
- Import and parse a WireGuard `.conf` profile using the official WireGuard tunnel library.
- Android system VPN consent prompt via `VpnService.prepare()`.
- Real connect/disconnect calls to the WireGuard userspace backend.
- Profile contents are held in memory for the current app session and are not uploaded by this app.
- GitHub Actions workflow to build a debug APK.

## What is still required before general release

This repository does **not** currently operate a VPN gateway and does not contain any pre-provisioned server credentials. A valid WireGuard profile must come from a VPN server that the user owns or is authorized to use. A profile contains sensitive key material; do not commit it to GitHub or share it publicly.

The client cannot make an arbitrary server work by inventing an IP address, public key, or private key. A successful tunnel also depends on the server being online and configured to route traffic and DNS correctly.

## Build

The workflow `.github/workflows/android-debug-apk.yml` runs `gradle assembleDebug` from this directory and uploads `app-debug.apk` as a workflow artifact. The debug APK is for testing and is not a Play Store release.

## Device coverage

The WireGuard tunnel library provides native binaries for common Android ABIs. This is an Android application, not a universal binary: iOS, Windows, macOS, and Linux need their own native clients or platform-specific packaging.

## Privacy and permissions

Android asks the user to authorize VPN access. This is a required operating-system consent, not a LivreNet account. The app does not promise free or unlimited mobile data and does not bypass carrier plans or restrictions.
