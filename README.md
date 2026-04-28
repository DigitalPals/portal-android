# Portal Android

Portal Android is the Android version of [Portal](https://github.com/DigitalPals/portal).
It is a companion client for [Portal Hub](https://github.com/DigitalPals/portal-hub)
and only works in combination with a Portal Hub deployment.

Portal Android is not a standalone SSH, Telnet, local shell, or VNC client. The
app connects to Portal Hub for authentication, shared state, key-vault flows, and
persistent terminal sessions.

## Requirements

- Android device or emulator supported by the current Gradle configuration
- A reachable Portal Hub instance
- Portal Hub configured for the Android OAuth client and redirect URI:
  `com.digitalpals.portal.android:/oauth2redirect`

Portal Hub should normally be exposed only on a private network, such as a
Tailscale tailnet. Do not expose Portal Hub directly to the public internet.

## Relationship to Portal

[Portal](https://github.com/DigitalPals/portal) is the desktop application for
macOS and Linux. Portal Android targets the Android client experience for the
same Portal Hub-backed workflows:

- sign in to Portal Hub from Android;
- enroll and unlock Android access to the Portal key vault;
- list and resume Hub-managed terminal sessions;
- sync Portal data formats that are shared with Portal Hub.

Changes to pairing, authentication, connectivity, or shared data formats should
be checked against both upstream projects:

- Portal: <https://github.com/DigitalPals/portal>
- Portal Hub: <https://github.com/DigitalPals/portal-hub>

## Build

Open the project in Android Studio, or build from the command line:

```sh
./gradlew assembleOssDebug
```

Run the unit tests with:

```sh
./gradlew test
```

The app package id is `com.digitalpals.portal.android`. Debug builds add the
`.debug` suffix.

## License and Attribution

Portal Android is licensed under the Apache License, Version 2.0. See
[LICENSE](LICENSE) for the full license text.

This repository includes Android terminal, SSH, storage, and UI code derived
from the Apache-2.0 licensed ConnectBot project. Existing source notices and
copyright headers are retained where applicable.

Portal and Portal Hub are separate upstream projects with their own licenses.
