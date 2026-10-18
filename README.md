# OpenSSTP — Windows source and Android SoftEther port

This repository contains the original Windows UI/source and an Android port that uses a native SoftEther protocol module rather than the old SSTP code.

## Android port status

- Android application source: `Android-Port/`
- Native engine dependency: `hoang-rio/SoftEther-Android-Module`, fetched with the bootstrap script and its Git submodules.
- Android data plane: `VpnService` TUN ↔ SoftEther native send/receive; protect native sockets before installing default routes; request IP settings over the SoftEther tunnel; report connected only after DHCP and TUN creation succeed.
- UI: dark Open-SSTP styling, connection status, traffic rate display, and encrypted **Remember me** preferences.
- The legacy SSTP implementation from `reference/` is not included or used by this Android port.

The Android module is a native implementation of the SoftEther protocol based on the upstream SoftEther reference source; it does **not** execute Windows `vpnclient.exe` on Android. For source/license detail and limitations see `Android-Port/README-fa.md` and `THIRD_PARTY.md`.

## Build Android APK

From the repository root, first fetch and prepare the native module:

```bash
./scripts/bootstrap-softether.sh
python3 scripts/validate-android-port.py
cd Android-Port
gradle assembleDebug
```

On Windows use `scripts/bootstrap-softether.ps1`. Android Studio can open `Android-Port` after the bootstrap step. Required build components: JDK 17, Android SDK 36, Android NDK, and CMake 3.22.1.

A GitHub Actions workflow at `.github/workflows/android-build.yml` performs the static checks, fetches the native source, compiles the debug APK, and publishes `OpenSSTP-Android-SoftEther-debug` as an artifact. It runs on demand or when relevant source files are pushed.

**Important:** this development environment does not have Android SDK/NDK or outbound git access, so it has not produced an APK here and cannot confirm a live VPN session. The Android source includes the real connection and packet-bridge path, but compilation and testing against a reachable server/device are still required before calling the app release-ready. Static check success alone does not prove internet connectivity.

## Windows

The original Windows project remains in `OpenSSTP-Windows/`. Its independent Windows build workflow is in `.github/workflows/build.yml`.
