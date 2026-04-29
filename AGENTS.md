# Agent Notes

This project is the Android version of Portal.

## Development Environment

- Run commands from the repository root.
- `direnv` is expected. If the environment is not active, run `nix develop`.
- Do not install missing Android, Java, or build tools globally; add project-specific tools and libraries to `flake.nix`.
- Common commands: `./gradlew assembleOssDebug`, `./gradlew test`.

Keep these upstream references in mind when making product, protocol, compatibility,
or UX decisions:

- Portal: https://github.com/DigitalPals/portal
- Portal Hub: https://github.com/DigitalPals/portal-hub

The Android app should support Portal Hub, so changes that affect pairing,
connectivity, authentication, or shared data formats should be checked against both
upstream projects when relevant.
