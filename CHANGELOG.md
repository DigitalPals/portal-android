# Changelog

All notable Portal Android changes should be documented in this file.

This project follows semantic versioning while the public Android app and Portal
Hub protocol support stabilize.

## [Unreleased]

### Changed

- Removed the unreachable legacy ConnectBot code paths (classic UI graph,
  SSH/Telnet/local transports, Room database layer, terminal service stack,
  and vendored third-party sources) along with their dependencies; the app is
  now only the Portal Hub client plus the shared terminal-emulator glue.
- Portal dependencies (`PortalStore`, `HubClient`, `PortalHubRepository`) are
  now provided through Hilt, and `PortalViewModel` is constructor-injected.
- Extracted the vault enrollment lifecycle into `VaultEnrollmentManager`.
- Plain Portal Hub HTTP calls now use a 30-second read timeout (SSE and
  terminal WebSocket streams remain unbounded) and surface structured server
  errors instead of raw response bodies.
- Disabled Android backup: Portal secrets are bound to this device's Android
  Keystore and would not be restorable on another device.

### Added

- Behavioral unit tests for the `PortalViewModel` pairing, vault enrollment,
  terminal, host editing, and sign-out flows.
- CI now checks out the Portal Hub contract schemas and fails (instead of
  silently skipping) when contract validation cannot run, and builds the
  debug APK.

## [0.1.1]

### Changed

- Renamed the project documentation and app-facing identity to Portal Android.
- Documented that Portal Android only works with Portal Hub.
- Kept the repository license as Apache-2.0 and retained attribution for
  inherited Android code.

## [0.1.0]

### Added

- Initial Portal Android app package: `com.digitalpals.portal.android`.
- Portal Hub sign-in and Android OAuth redirect handling.
- Portal Hub-backed terminal, session, settings, and vault screens.

## Legacy Attribution

Portal Android includes code derived from the Apache-2.0 licensed ConnectBot
Android project. Source headers and license notices are retained where
applicable.
