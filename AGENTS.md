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

## Human Testing Before Release

- The default Portal Hub human-testing target is the LXC at `root@10.10.0.13`.
- Before creating a new GitHub release for changes that affect Portal Hub behavior,
  Android pairing, authorization, vault access, SSH proxying, sync, or cross-client
  compatibility, test the feature on this LXC after automated checks pass.
- Use SSH to access the LXC. Treat it as the staging Portal Hub environment for
  release validation, not as a place for untracked source changes.
