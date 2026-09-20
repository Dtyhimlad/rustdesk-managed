# RustDesk Managed Android

A managed Android fork of [RustDesk](https://github.com/rustdesk/rustdesk) focused on reliable unattended access for self-hosted deployments.

> **Status:** active development. The working development branch is `managed-android`, pinned to the upstream RustDesk **1.4.9** release baseline.

## Project goals

This fork is intentionally focused on functionality rather than branding.

The Android client is being adapted to provide:

- a preconfigured self-hosted RustDesk rendezvous/relay server;
- a predefined permanent unattended-access password;
- automatic registration of the device and RustDesk ID with our management platform;
- automatic startup of the RustDesk listener/service when the app opens;
- automatic startup after Android boot;
- separation of the RustDesk listener from MediaProjection, so merely making the device reachable does **not** immediately trigger the Android screen-sharing prompt;
- screen-capture permission only when an incoming remote-desktop session actually requires video;
- compatibility with managed/ADB-provisioned MediaProjection app-op setups where Android permits them;
- removal/disablement of the floating stop-service window while retaining the Android foreground-service notification required by the operating system.

## Development baseline

The `managed-android` branch starts from upstream RustDesk **1.4.9**:

- upstream commit: `6c578292e8ebbbec708b76986ba8c4bc7c509747`
- Flutter: **3.24.5**
- Rust: **1.75**
- cargo-ndk: **3.1.2**
- Android NDK: **r28c**
- Java: **17**

These versions match the upstream 1.4.9 Android CI configuration.

The repository's `master` branch may continue to follow newer upstream RustDesk development. Managed Android changes should be made against `managed-android` unless the baseline is deliberately upgraded.

## Build strategy

Android builds are produced in GitHub Actions on an x86-64 Ubuntu runner rather than on the local Apple Silicon development machine.

Local development is done on macOS with Android Studio and ADB. GitHub Actions produces the APK, which can then be installed on a test device with:

```bash
adb install -r rustdesk-managed-arm64.apk
```

The initial CI target is **ARM64 / arm64-v8a**. Other Android ABIs can be added after the managed ARM64 build is stable.

## Development roadmap

- [x] Fork created
- [x] Stable RustDesk 1.4.9 development branch created
- [x] Reproducible stock ARM64 Android build in GitHub Actions
- [x] Start RustDesk listener automatically when the app starts
- [x] Start listener automatically after device boot
- [x] Decouple listener startup from MediaProjection permission
- [x] Request/start screen capture only for an actual remote-control session
- [x] Disable the floating stop-service window
- [x] Preconfigure the self-hosted RustDesk server and key
- [x] Configure the permanent unattended password
- [ ] Automatically register RustDesk ID/device metadata with the management platform
- [ ] Make registration idempotent so reinstalling an existing RustDesk ID does not create duplicates
- [ ] Add update/distribution workflow for the self-hosted installer page
- [ ] Expand builds to additional Android ABIs if required

## ADB / MediaProjection testing

A normal development/test device does **not** need to be rooted.

On Android/OEM versions where the app-op is supported, MediaProjection behaviour can be tested with:

```bash
adb shell appops set <package.id> PROJECT_MEDIA allow
```

This is treated as an optional provisioning mechanism, not as something the application can assume will behave identically on every Android release or vendor ROM.

## Distribution

The intended distribution model is private/self-hosted deployment through the existing installer portal used for the Windows and Linux RustDesk installers.

The Android APK will eventually be published there as an additional installer option.

## Upstream

This project is derived from RustDesk:

- Upstream source: https://github.com/rustdesk/rustdesk
- Upstream documentation: https://rustdesk.com/docs/
- Upstream server: https://github.com/rustdesk/rustdesk-server

This repository is an independent managed fork and is not an official RustDesk release.

## License

RustDesk is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0). This fork retains the upstream licensing requirements.

See [LICENCE](LICENCE) and the upstream RustDesk repository for the full license and attribution information.

## Authorized use

This project is intended for systems that the operator owns or is authorized to administer. Remote-access deployment should comply with applicable law, organizational policy, and the permissions of the device owner/user.
