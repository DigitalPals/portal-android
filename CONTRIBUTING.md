# Contributing

Portal Android is the Android companion for Portal and requires Portal Hub for
its supported workflows.

Before changing pairing, authentication, connectivity, vault handling, terminal
session behavior, or shared data formats, compare the change with:

- Portal: <https://github.com/DigitalPals/portal>
- Portal Hub: <https://github.com/DigitalPals/portal-hub>

## Development

Build the app:

```sh
./gradlew assembleOssDebug
```

Run unit tests:

```sh
./gradlew test
```

Run lint and checks when a change touches Android resources, Gradle files, or
shared infrastructure:

```sh
./gradlew lint check
```

## Guidelines

- Keep Portal Hub compatibility explicit.
- Do not add standalone Android SSH, Telnet, local shell, or VNC entry points.
- Preserve existing Apache-2.0 source notices and third-party attribution.
- Keep changes scoped and add tests for protocol, vault, sync, or persistence
  behavior.
