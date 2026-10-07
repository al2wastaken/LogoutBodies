# LogoutBodies

LogoutBodies leaves a disconnected player's real server-side `ServerPlayer` in the world for 600 server ticks by default. It retains the player's UUID, name and skin profile, exposes a Bukkit `Player`, and receives native damage, interaction and death events. It does not register the body as an online player or fire synthetic join/quit events.

This is a development release. Compilation and automated tests are not a claim of complete production certification. See [Validation](#validation) for executed checks and remaining acceptance work.

## Version matrix

The release inventory was checked on 2026-10-07 against the [official Paper API](https://fill.papermc.io/v3/projects/paper/versions) and [Leaf releases](https://github.com/Winds-Studio/Leaf/releases). Only stable server releases are targets. Each artifact accepts its exact Minecraft version; unknown versions fail with an explanatory startup error.

| Minecraft | Server baseline | Minimum server Java | Artifact module | Validation |
| --- | --- | --- | --- | --- |
| 1.21.11 | Paper 132; Leaf 179 | 21 | `platform-1_21_11` | Full protocol suites passed |
| 26.1.2 | Paper 74 | 25 | `platform-26_1_2` | Full protocol suite passed |
| 26.2 | Paper 132 | 25 | `platform-26_2` | Startup smoke only |

26.2 has a compiled adapter but remains pending full client validation. Paper 26.3 is BETA and 26.1.1 is ALPHA in this inventory. Newer Leaf releases with development warnings are not validated targets. Future releases require source review, an explicitly pinned bundle and server tests before being added. Spigot and Folia are outside the support scope. No third-party plugin dependencies, detection or compatibility branches are present.

## Build and installation

Build with JDK 25 and the checked-in Gradle 9.7.1 Wrapper:

```powershell
.\gradlew.bat build
```

On Linux or macOS:

```sh
bash ./gradlew build
```

The API/core and 1.21.11 adapter use Java 21 bytecode; 26.x adapters use Java 25 bytecode. The build JVM requirement is separate from the server runtime requirement. Paperweight `2.0.0-beta.24` and exact development bundles are pinned. All output uses Mojang mappings; 26.1+ has no reobfuscation step, consistent with [Paper's userdev documentation](https://docs.papermc.io/paper/dev/userdev/).

Install exactly one matching `platform-*/build/libs/LogoutBodies-<minecraft>-0.1.0-SNAPSHOT.jar` in `plugins/`. Do not install core, API or integration probe JARs as plugins. SQLite is bundled. Start the server normally. Back up the entire world/player data and `plugins/LogoutBodies` together before updating. Stop the server to change artifacts; do not hot reload plugins.

```yaml
body-lifetime-seconds: 30
debug: false
```

Lifetime is converted to server ticks, so lag extends elapsed wall-clock time. `/logoutbodies reload` affects newly created bodies. Existing deadlines stay unchanged. Valid lifetime range: 1–86400 seconds. Debug logs report creation, removal, damage and teleport activity.

## Ownership and recovery

The active body owns health, absorption, inventory, armor/offhand and XP. After the ownership journal commits, the retiring player entity's inventory/XP are cleared before vanilla saves it. Its original offline player file is not another item source. Insertion waits until the original entity has left the online registry. Reconnect cleanup runs on the server thread from the asynchronous pre-login barrier, before vanilla loads and inserts the real player. The final snapshot is saved, read back, verified and flushed before the journal handoff completes. Removed body references have their inventory and XP cleared.

Expiry, administration, world unload and plugin shutdown retain the final state for the next login. A living owner inherits the body's final position. Native death determines `keepInventory`, final inventory, event-modified drops and XP. The owner returns alive at the native respawn destination; no second death or drop is generated at login.

`ownership.db` uses SQLite WAL with FULL synchronization. Each session is also a unique death/drop operation identifier. Death commits the post-event state and `PREPARED` drop payload, then persists `APPLYING`, emits drops and records `DONE`. On restart, definitely unstarted `PREPARED` operations are executed once when their world is loaded. `APPLYING` becomes `AMBIGUOUS` and is **never replayed**. Ambiguous records remain available for inspection after login.

Minecraft files/world saves and SQLite cannot share one atomic transaction. A crash can therefore lose items or XP. To avoid restoring stale copies after an unclean ACTIVE session, its uncertain inventory/XP are withheld on login; the durable snapshot stays in the journal for administrator inspection. The same conservative policy applies to ambiguous drop emissions, where item-spawn callbacks may have transferred possessions. Normal completed history is pruned after seven days; ambiguous and unclean records are retained. Never delete or edit the journal to bypass reconciliation. A storage failure blocks affected logins; initialization failure keeps a login guard active until the problem is fixed and the server restarted.

## Commands

All commands require `logoutbodies.admin`, which defaults to OP. Console is supported.

| Command | Purpose |
| --- | --- |
| `/logoutbodies list` | Active owner UUIDs and remaining ticks |
| `/logoutbodies remove <player\|uuid>` | Settle and remove a body without killing it |
| `/logoutbodies reload` | Reload configuration for future bodies |
| `/logoutbodies journal` | Read-only view of the latest 100 pending/uncertain recovery records |

## API

The `api` module contains no NMS classes and can be published independently with `:api:publishToMavenLocal` as `io.github.logoutbodies:logoutbodies-api:0.1.0-SNAPSHOT`. Consumers compile against it, depend on `LogoutBodies` in their plugin descriptor, and do not shade their own API copy.

```java
// Call from the server thread, after LogoutBodies has enabled.
LogoutBodiesApi api = Bukkit.getServicesManager().load(LogoutBodiesApi.class);
if (api != null && api.isLogoutBody(event.getEntity())) {
    UUID owner = api.getOwnerUuid(event.getEntity()).orElseThrow();
    api.getBodyInfo(owner).ifPresent(info -> logger.info(info.sessionId().toString()));
}
```

`isLogoutBody(Entity)`, `getOwnerUuid(Entity)`, `getBody(UUID)` and `getBodyInfo(UUID)` require the main server thread. `BodyInfo` is a detached immutable view safe to retain. Entity access is not safe off-thread. Identification uses entity reference identity, never `Player.equals()` (which compares UUIDs). The real owner is not the body even though their UUIDs match. Do not cache a body as an independent inventory source.

`LogoutBodySpawnEvent` and `LogoutBodyRemoveEvent` are synchronous notifications. They are not cancellable. The removal event's entity is already removed and its inventory cleared; use its immutable info for attribution. Damage/death/interaction use ordinary Bukkit events. A body is not returned by `Bukkit.getPlayer()` and cannot be treated as a connected client. Asynchronous teleport APIs and packet-dependent menus are not part of the body contract; synchronous teleport and messaging are tested.

## Boundaries

Only the listed ownership fields are transferred. Ender chests, advancements, statistics, arbitrary plugin metadata and the full original player NBT are not body-owned snapshots. Initial potion effects are copied into the live body, but their remaining duration is not transferred on a living reconnect; death clears effects/fire/fall state. A body cannot pick up loose items automatically. XP emission is journaled through Bukkit and does not preserve every native orb split or spawn-reason detail. Visual skin rendering and late-viewer behavior require the manual client checks listed below.

Bodies are genuine world players, so vanilla mechanics that iterate world players can observe them even though the online registry does not. Plugins must consult the API when that distinction matters. A body ticket can prevent world unload; remove bodies before forcibly unloading a world. Custom worlds containing pending owner snapshots must be loaded before those owners reconnect. Failed login reservations expire after 1200 server ticks. The database performs synchronous durable commits, batched for changed bodies each tick; benchmark expected body counts on your storage before deployment.

## Project structure and development

- `api/`: public interfaces, immutable body information and lifecycle events.
- `core/`: ownership, lifecycle, configuration, commands, chunk tickets and SQLite.
- `platform-common/`: shared mapped NMS/CraftBukkit code, compiled separately by each version module.
- `platform-1_21_11/`, `platform-26_1_2/`, `platform-26_2/`: pinned bundles and deployable artifacts.

CraftBukkit/NMS access belongs only in adapters. The API and core use Bukkit types. See [Repository Guidelines](AGENTS.md) for contribution conventions.

| Adapter | Pinned development bundle | Server source identifier |
| --- | --- | --- |
| 1.21.11 | `1.21.11-R0.1-20260511.115010-91` | Paper `c5eb0790f199da6c38d0a650e1e5cd5415b28185`; Leaf `12f438e06b8a35d6584687565ec5eeacb373972c` |
| 26.1.2 | `26.1.2.build.74-stable` | Embedded identifier `e4e17fc` |
| 26.2 | `26.2.build.132-stable` | Paper `19ebc4a49b725b1ea813f95ac559a2ed3931ee4c` |

Paper 26.1.2's build API provided no commit entries and its full source SHA could not be resolved publicly; the exact bundle coordinate is pinned instead. No reflective constructor guessing or unknown-version fallback is used.

## Validation

Local checks were performed on 2026-10-07 with Windows 11 and Temurin JDK 25.0.3:

| Check | Result |
| --- | --- |
| Build all three adapters | Passed |
| Local JUnit lifecycle, identity, snapshot, ticket and SQLite tests | 14 passed; none failed or skipped |
| Paper 1.21.11-132, Leaf 1.21.11-179, Paper 26.1.2-74 full protocol suites | Passed |
| Forced-process crash recovery on Leaf 1.21.11 and Paper 26.1.2 | Passed |
| Paper 26.2-132 startup, API, admin command and shutdown | Passed |
| Independent API Maven POM generation | Passed |

The full protocol suites covered UUID/Player identity, exclusion from the online registry, messaging, teleport, client attack and interaction, native damage/death, cancelled death, cross-world damage, body inventory edits, reconnect, modified drops, both keepInventory settings, expiry/ticket release and graceful restart. Forced-process tests checked safe owner login, withholding uncertain possessions and retaining recovery records.

**Not completed:** full 26.2 client tests (the local protocol library lacks support), authenticated-client skin/rendering and late-viewer acceptance, exhaustive disk-failure injection, production-scale load tests and a JDK 21 runtime run. These remain required before production certification.

Tests, integration fixtures, supplementary documentation and CI files are intentionally excluded from this source-only repository. Consequently, `build` on a fresh checkout compiles the artifacts but does not reproduce the 14 local tests; no remote CI run is claimed. If local fixtures are available, `:core:test` runs their JUnit tests and `-PintegrationProbe=true :integration-probe:jar` builds the probe. Use only disposable localhost servers and explicitly accept the Minecraft EULA before running server tests. Never deploy the probe or offline-mode test configuration in production.

Before deployment, use authenticated vanilla clients to check skins, tab-list exclusion, late joining, tracking-range changes, equipment, XP/drop counts and reconnect near expiry. Also check world unload/reload, storage failures and sustained body counts on the intended hardware.

## License

The repository retains its [GPLv3 license](LICENSE). Bundled SQLite JDBC retains its own upstream license notices. Generated artifacts and downloaded server/mapped sources remain outside Git; server code is not redistributed.
