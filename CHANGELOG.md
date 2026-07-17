# Changelog

All notable Portal Android changes should be documented in this file.

This project follows semantic versioning while the public Android app and Portal
Hub protocol support stabilize.

## [Unreleased]

### Changed

- Redesigned the entire app to the "Portal Android" dark design handoff:
  a mandatory onboarding flow (welcome → hub check via `/api/info` → browser
  OAuth → vault enrollment with editable device name → sync service
  selection), a five-tab shell (Hosts / Sessions / Snippets / Ports /
  Settings), grouped host lists with search and a new-host form, session
  cards with Hub terminal previews, snippet run sheets, and a terminal with
  session tabs, status header, and an extra-keys row. Ports is a placeholder
  until the Hub exposes a port-forwarding API.
- The terminal now supports multiple simultaneous Hub sessions in tabs;
  leaving the terminal keeps sessions attached, and ending a session from the
  Sessions tab signals the Hub.

### Added

- Host key verification: the app now answers the Hub's
  `host_key_verification` terminal WebSocket message with a fingerprint
  confirmation sheet (`host_key_response`), including changed-key warnings.
- Session previews: `/api/sessions` is queried with `include_preview` and the
  trailing log lines are rendered on session cards.
- Hosts can be created from the app and synced back to the Hub
  (`PUT /api/sync/v2` hosts service), including vault-key auth selection and
  host groups parsed from the synced profile.
- Portal Hub is treated as mandatory: every SSH host is connectable, the
  per-host "Use Portal Hub" toggles were removed, and any host created or
  edited on Android is written back with `portal_hub_enabled: true`.
- Closing the last terminal session now returns to the Hosts tab instead of
  Sessions.
- Vault key decryption (Argon2id) moved off the main thread: tapping a
  vault-key host now immediately opens the terminal with an "unlocking vault
  key…" indicator instead of freezing the hosts list for several seconds, and
  a decryption failure cleans up the pending tab with a visible error.

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
