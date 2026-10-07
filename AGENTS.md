# Repository Guidelines

## Project Structure & Module Organization

- `api/` contains the public Bukkit API, immutable body information, and lifecycle events.
- `core/` owns sessions, identity indexing, snapshots, chunk tickets, commands, configuration, and SQLite persistence.
- `platform-common/src/main/java/` contains shared CraftBukkit/NMS implementations, compiled independently by `platform-1_21_11/`, `platform-26_1_2/`, and `platform-26_2/` against pinned Paper bundles.
- Runtime resources live in `core/src/main/resources/` and `platform-common/src/main/resources/`.
- Local tests use `core/src/test/java/`, `integration-probe/`, and `integration-tests/`. These, supplementary documentation, downloaded servers, and build outputs are intentionally ignored. Preserve this policy; `AGENTS.md` and `README.md` are explicit documentation exceptions.

## Build, Test, and Development Commands

Use JDK 25 and the checked-in Gradle Wrapper (9.7.1). On Unix, replace `.\gradlew.bat` with `bash ./gradlew`.

- `.\gradlew.bat assemble` builds production artifacts without running tests.
- `.\gradlew.bat build` compiles all adapters and runs available tests.
- `.\gradlew.bat :core:test` runs local JUnit tests when their ignored sources are present; a fresh checkout has no test sources.
- `.\gradlew.bat -PintegrationProbe=true :integration-probe:jar` builds the optional local fixture when present.

Install only the matching `platform-*/build/libs/LogoutBodies-<minecraft>-*.jar`. Never install API, core, or probe artifacts as production plugins.

## Coding Style & Naming Conventions

Write code, comments, and repository documentation in English. Use four-space Java indentation, `PascalCase` types, `camelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. Follow existing `io.github.logoutbodies` packages. Format Java with google-java-format in AOSP mode when available. Use Kotlin DSL for Gradle configuration and pin dependency versions.

## Testing Guidelines

Local tests use JUnit Jupiter 5.12.2 and names such as `OwnershipJournalTest`. No coverage percentage is configured. Verify meaningful lifecycle transitions, reference identity, shared tickets, and persistence interruption boundaries. NMS changes require matching real-server checks; compilation alone does not establish support. Record executed checks and untested scenarios in the PR. Test servers must be disposable, localhost-only, and use an explicitly accepted Minecraft EULA.

## Commit & Pull Request Guidelines

History uses concise imperative subjects, such as `Implement multi-version LogoutBodies plugin`; no mandatory Conventional Commits format exists. Keep commits focused. PR descriptions should explain behavior, affected versions, validation, and remaining limitations; link relevant issues. Do not force-add ignored tests, Markdown, binaries, or local configuration.

## Architecture & Safety

Keep NMS imports inside adapters. Access entities only on the server thread and identify bodies by reference, never `Player.equals()`. Preserve durable ownership before spawning or dropping items; never replay ambiguous drops. Retain GPLv3 and avoid third-party plugin detection or compatibility branches.
