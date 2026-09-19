# Bobby 1.18.2 — Backport Plan (v3.1.1 → v5.2.15)

Goal: bring every **behavioural** improvement made between Bobby 3.1.1 (MC 1.18.2, 2022-03-12) and
5.2.15 (MC 26.2, 2026-07-12) into a build that **stays on Minecraft 1.18.2**.

- Base source: tag `v3.1.1` of the upstream clone (MC 1.18.2, Yarn `1.18.2+build.2`, Java 17).
- Reference: current `master` in the `bobby` workspace repo (MC 26.2, Mojang mappings).
- Working repo: `C:\Users\Andy\Documents\Code\bobby-1.18.2`, branch `mc-1.18.2`.
- Superset: **162 commits, 69 files, +4123 / −1030 lines** between `v3.1.1` and `v5.2.15+mc26.2`.
  Of those, only **~36 commits are feature/bugfix work**; the rest is Minecraft/Loom/Gradle bumps,
  version bumps and release chores.

---

## Status

| Stage | State |
| --- | --- |
| 0 — Branch + buildable baseline | **done** |
| 1 — Quick wins (13 fixes) | **done** |
| 2 — Chunk pipeline | **done** — all 9 commits landed (`48932ee`, `6c477c3`, `1c8710a`, `c8c3f06`, `c90f1dc`, `a949516`, `dc98416`, `9138db9`, `6f9d7be`). |
| 3 — Dynamic multi-world | **done** — `cc42d7f`, `589e1cc`, `a47a957`, `6a50b8e`, `6336732`, `83fa70d`, `e5db1a5`, `416fd25`, `8ac8f1b`, `1169564` (`d9f74e3` skipped). |
| 4 — Translations & polish | **done** — all 12 language files taken from `v5.2.15`; `en_us.json` already matched key-for-key. `fabric.mod.json` contact block updated (Modrinth homepage + issues). |
| 5 — Verification | **static checks done, runtime still to do.** See below. |

**Repo:** `C:\Users\Andy\Documents\Code\bobby-1.18.2` — a clone of upstream, on branch `mc-1.18.2`
(based on tag `v3.1.1`, commit `d94c664`), with `upstream` as the remote. Every backport is a real
cherry-pick, so `git log` preserves provenance.

### Toolchain change (unavoidable)

The 1.18.2 project originally used Gradle 7.3 + Loom `0.10-SNAPSHOT`, neither of which can run on the
JDKs available here (21 / 25; there is no JDK 17 installed), and `jcenter()` in `settings.gradle.kts` no
longer exists. It was bumped to the combination upstream itself used for its 1.21 builds, which is
therefore well-tested:

- Gradle **8.10** (wrapper), Loom **1.7.3**, Modrinth Minotaur **2.+** (the old `TaskModrinthUpload` API
  no longer exists in 2.x, so that block was rewritten to the modern DSL)
- Run Gradle with `JAVA_HOME` pointing at JDK 21; `sourceCompatibility`/`targetCompatibility` stay at 17.

### Deviations from a pure cherry-pick

| Commit | Deviation | Why |
| --- | --- |
| `df84a5a` | **skipped** | Fix for a Sodium **0.5**-only design (on that branch the status-listener class does not even exist). 1.18.2 uses Sodium 0.4. |
| `3cfff04` | **rewritten** | Upstream injects into the lambda calling `applyFog(FOG_SKY, ...)`. That lambda is a *synthetic* method (`method_37365`) which Mixin cannot resolve on 1.18.2 (`Cannot find target method`), even with an explicit descriptor. The clamp now lives in `BackgroundRendererMixin` as a second `@ModifyVariable` on `applyFog`, filtered on `FogType.FOG_SKY` - same effect, and it leaves the terrain fog untouched. Also, 1.18.2's `applyFog` takes **4** args (no `tickDelta`), not 5. |
| `7877b1e` | **adapted** | 1.18.2 has only the no-arg `getCurrentWorldOrServerName()`, and the cache path re-resolved the name instead of reusing it. The name is now resolved once and reused for both the emptiness check and the path. |
| `53c73b9` | **partial** | Only the `setBlockState` guard was taken; the surrounding `setHeightmap` belongs to a later stage. |
| all | `CHANGELOG.md` | Always resolved as *ours*; the backport keeps its own changelog. Revisit at Stage 4. |
| `c20b228`, `3cfff04` | `bobby.mixins.json` | Conflicts resolved by keeping only entries whose `.java` file actually exists, so entries belonging to skipped commits (`ClientWorldAccessor`, `SimpleOptionAccessor`, `ValidatingIntSliderCallbacksAccessor`) are dropped. |
| `6c477c3` | **adapted** | Kept the synchronous `loadTag` (upstream's async form is a 1.19 API artifact) and standardised on commons-lang3 `Pair`, which MC bundles on 1.18.2 too, so all three files agree on one `Pair` type. |
| `1c8710a` | **manual extraction** | Merging would have mangled a 320-line region, so the serialization code was cut out of `FakeChunkStorage` (575 → 244 lines) into `ChunkSerializer` by a brace-matched script, preserving upstream's public API (`serialize`, `deserialize`, `loadChunk`, `shallowCopy`, `floodSkylightFromAbove`, `logRecoverableError`) so later commits still line up. The 1.18.2 bodies were kept (e.g. `PalettedContainer.createCodec`, `net.minecraft.util.registry.*`). |
| `c8c3f06` | **adapted** | Adopted upstream's new `getRegions(Path)` helper and `util/RegionPos`, deleted the inner record, and reverted a leaked `Registries.CHUNK_GENERATOR` back to `Registry.CHUNK_GENERATOR`. |
| `c90f1dc` | **adapted** | Upstream's intent (drop the storage from `loadTag`'s result) applied to the synchronous form: `loadTag` now returns `NbtCompound` directly. |
| `a949516` | **adapted** | `Status: "full"` committed; also took the neighbouring `isLightOn` line, which is correct here because our serializer does write per-section `BlockLight`/`SkyLight`. |
| `dc98416` | **adapted** | `LightData` turned out to exist in 1.18.2 with an identical API, so this needed almost nothing: `packet.getChunkX()/getChunkZ()` → `getX()/getZ()`, and the import block rebuilt with 1.18.2 registries. The shadowed `ClientPlayNetworkHandler.world` field and `readLightData(int,int,LightData)` both match 1.18.2. |
| `9138db9` | **adapted** | Upstream calls `LightingProviderExt.bobby_disableColumn` (added by the 1.20.1 bump), which does not exist here; that line was dropped. The fix's own machinery ported as-is, except the network handler is read from `client.getNetworkHandler()` + a null fallback, since 1.18.2 has no `ClientWorldAccessor`. `ClientWorld.enqueueChunkUpdate` exists in 1.18.2, so the `@ModifyArg` injection resolves. |
| `6f9d7be` | **half-applied** | The substantive half (moving the fake-chunk unload injection to after vanilla's `getIndex` bounds check) applied cleanly. The other half guards `fingerprint(chunk)`, which belongs to Stage 3's multi-world work and does not exist yet - a `Stage 3 NOTE` comment marks the spot. |
| `cc42d7f` | **adapted** | `Worlds.java` (1565 lines) came in as a new file and needed: `net.minecraft.registry.*` → `net.minecraft.util.registry.*`, `Text.translatable/literal` → `TranslatableText`/`LiteralText` constructors, `NbtTagSizeTracker.ofUnlimitedBytes()` → `new NbtTagSizeTracker(Long.MAX_VALUE)`, and `NbtIo.readCompressed(in, tracker)` → `readCompressed(in)`. The `storages` list stays upstream-shaped (`Function<ChunkPos, CompletableFuture<Optional<NbtCompound>>>`) but each entry resolves immediately, wrapping 1.18.2's synchronous `FakeChunkStorage.loadTag` so the merge algorithm is untouched. Commands use `fabric-command-api-v1` (`ClientCommandManager.DISPATCHER`), since v1 has no `ClientCommandRegistrationCallback`. `getCurrentWorldOrServerName()` keeps its no-arg form. |
| `d9f74e3` | **skipped** | Pure `submit` → `execute` rename (a 1.19 API change). Both methods exist on 1.18.2, but `submit` is the 1.18 idiom, so the rename is cosmetic here. |
| `8ac8f1b` | **adapted** | Took upstream's fix (read `matching`/`mismatching` from `matchNbt`, not `worldNbt`) but dropped `.orElseThrow()`, since 1.18.2's `NbtCompound.getLongArray` returns `long[]` rather than an `Optional`. |
| `1169564` | **adapted** | The path-traversal guard landed intact (`FileSystemUtils.resolveChild`), but it also re-introduced upstream's `ClientWorldAccessor`-based server-name lookup, producing a duplicate local; that line was removed in favour of the no-arg helper. |

**Rule of thumb for the remaining stages:** a Mixin AP warning at build time means the mixin will fail at
runtime. Treat `warning: Unable to determine descriptor` / `Cannot find target method` as hard errors.

### Stage 5 progress: static checks (complete)

Everything verifiable without launching the game has been checked:

- Build is green with **zero Mixin AP warnings**, i.e. every `@Inject`/`@ModifyArg`/`@ModifyVariable` target
  resolves against real 1.18.2 classes. Mixin only fails at runtime *after* warning, so a warning-free build
  is the strongest static signal available. (This is exactly how the `WorldRendererMixin` problem surfaced.)
- The **packaged** `bobby.mixins.json` inside the built jar lists precisely the 14 mixin classes compiled into
  it - verified by reading the jar, not the source tree.
- **No reflection or hardcoded-class-name hazards anywhere in the source.** Loom remaps compiled code but not
  string literals, so a `Class.forName("net.minecraft...")` or `getName().contains(...)` check would work in
  dev and fail for users. `MixinConfigPlugin` detects optional mods by mod id and by the *mod's* package
  prefix (`ca.spottedleaf.starlight.`), neither of which is ever remapped - so it is safe.

Runtime verification (the test matrix below) is still outstanding: chunk persistence, multi-world merging and
the light/flicker fixes have not been executed yet.

### Stage 5 runtime findings (first launch)

**1. Client crashed during startup - `9138db9` injection point was wrong.**

```
InjectionError: Critical injection failure: Argument modifier method addUnloadFakeLightDataTask(...)
in bobby.mixins.json:ClientPlayNetworkHandlerMixin from mod bobby failed injection check,
(0/1) succeeded. Scanned 0 target(s).
```

Cause: upstream (1.19+) calls `ClientWorld.enqueueChunkUpdate` directly from `onChunkData`, but 1.18.2 splits
that out into a separate `updateChunk(int, int, LightData)`. Fixed by targeting `updateChunk`.
The other three `@At(INVOKE)` targets were then verified against real bytecode and are correct:
`onChunkData`->`loadChunk`, `loadChunkFromPacket`->`getIndex`, `ClientSettingsC2SPacket.write`->`writeByte`.

**Lesson (important):** a warning-free build does **not** prove that injections resolve. Mixin's annotation
processor validates that the *target method* exists, but never validates `@At` targets - so a stale injection
point compiles silently and only fails at runtime, taking the whole client down. Any `@At(INVOKE)` ported to a
new Minecraft version must be confirmed with `javap -p -c` against the target method's bytecode.

**2. Client crashed during startup - `BackgroundRendererMixin` handler signature was invalid.**

```
InvalidInjectionException: @ModifyVariable handler method clampSkyFogMaxValue has an invalid signature.
Found unexpected argument type boolean at index 3, expected float.
Expected signature: (F Camera FogType F Z)F
```

Cause: a `@ModifyVariable` handler which captures the target's arguments must declare the **complete**
argument list, in declaration order, after the modified value - you cannot capture only the subset you need.
The handler now takes `(float value, Camera, FogType, float viewDistance, boolean thickFog)` and filters on the
third captured argument. Injector handler signatures are likewise not validated at build time; the other ~20
handlers were audited by hand and are correct, since each either takes no target arguments or all of them.

### CI / release build (`.github/workflows/release.yml`)

Dependency resolution had to be fixed before CI could work at all:

- **Starlight's old ivy URL is dead.** `https://cdn.modrinth.com/data/H8CaAYZC/versions/Starlight 1.0.0:fabric.d0a3220 1.18.x/...`
  now returns **404** (that URL shape relied on Modrinth's legacy human-readable CDN paths). It is resolved
  as `maven.modrinth:starlight:1.0.2+1.18.2` from `https://api.modrinth.com/maven` instead.
- **The dependency is required even though no code references Starlight types.** `ChunkLightProviderMixin`
  names the Starlight classes only as strings (`@Mixin(targets = {...})`), but Mixin's annotation processor
  *resolves those strings against the compile classpath*, so removing the dependency fails the build with
  `Mixin target ca.spottedleaf.starlight.common.light.StarLightInterface$1 could not be found`.
  Do not "clean up" this dependency again.
- **A working local build is not evidence that dependencies resolve.** It only worked here because
  `~/.gradle/caches/...` already contained `com.modrinth.starlight` from earlier upstream builds. Reproduce a
  clean machine with a throwaway Gradle home: `.\gradlew.bat build -g "$env:TEMP\gradle-clean-sim"`.
  That is what exposed the 404.

Version plumbing the workflow relies on (verified, don't change casually):

| Thing | Value |
| --- | --- |
| Jar name | `build/libs/bobby-<modVersion>+mc<minecraftVersion>.jar` |
| `fabric.mod.json` version | `<modVersion>+mc<minecraftVersion>` (Loom expands `project.version`) |
| CHANGELOG.md first line | exactly `### <modVersion>` - `readChangelog()` asserts this **at configuration time** |
| Release tag | `v<modVersion>+mc<minecraftVersion>`, e.g. `v5.2.15.1+mc1.18.2` |

The workflow is **manual-only** (`workflow_dispatch`): `Actions -> Build and Release -> Run workflow`, then set
the two inputs and run it. Both are pre-filled for the usual release, so the normal run is just two clicks:

| Input | Default | Meaning |
| --- | --- | --- |
| `ref` | `mc-1.18.2` | Branch/ref that gets built and released (empty tag fallback reads its `gradle.properties`) |
| `tag` | `v5.2.15.1+mc1.18.2` | Release tag; leaving it empty falls back to `v<version from gradle.properties>` |

What gets built is decided by the `ref` input, **not** by the branch the workflow was dispatched from, so this
file is kept byte-identical on both `master` and `mc-1.18.2` and can be run from whichever branch you happen to
be on. GitHub only offers the "Run workflow" button for workflows that exist on the repo's **default branch**
(here `master`), which is why the copy on `master` exists - when editing this file, edit **both**.

The run refuses to continue if the requested tag does not match the checked-out `ref`'s `gradle.properties`
version, and publishes the release using the first `###` section of `CHANGELOG.md` as the body, with
`target_commitish` pinned to the built commit (otherwise the tag would be created at the default branch's HEAD).
It deliberately does **not** bump versions: bump `gradle.properties` + `CHANGELOG.md` on the branch being
released, commit, then run it.

### The main hazard: "version bump" commits are not just version bumps

This is responsible for almost every conflict in Stage 2. Commits such as `f0be144` (Update to 1.19) and
`a462a9b` (Bump to 1.19.2) look like pure chore commits, but they also absorbed **API-forced refactors**:

- MC 1.19 made `VersionedChunkStorage.getNbt` asynchronous, which is why upstream's `FakeChunkStorage.loadTag`
  and `FakeChunkManager.loadTag` return futures and why the `storages` list exists. On 1.18.2 the storage API is
  **synchronous** and must stay that way.
- They also carried incidental changes that later commits assume are present: a `java.util.List` import, the
  `isLightOn` NBT field, the javadoc block above `shallowCopy`, `Registries.CHUNK_GENERATOR` (1.19.3+) instead of
  `Registry.CHUNK_GENERATOR`, and `net.minecraft.registry.*` / `LightData` / `LightingProviderExt` packages.

Consequence: a 3-way cherry-pick can silently pull 1.19+ code into a method body, and the "skip version bumps"
rule from §0 must be applied to the *commit*, not to the *API* it introduced. Compile after every pick and grep
for 1.19-only symbols (`net.minecraft.registry.`, `LightData`, `Registries.`, `Registries`, `CompoundTag`).

---

## 0. Ground rules

1. **Port behaviour, not the Minecraft bump.** Every `Update to Minecraft X` commit is out of scope.
2. **Stay on Yarn mappings.** Upstream switched to Mojang mappings only on 2025-12-16 (`ababd15`),
   i.e. after `v5.2.11`. Commits before that cherry-pick with *zero renaming*; the two later ones need
   hand-translation.
3. **Stay on Java 17.** The 26.2 code targets Java 25. Any record/pattern-matching/`SequencedCollection`
   usage in ported code must be rewritten for 17.
4. **Keep the 1.18.2 options stack.** `GameOptions.viewDistance` + `Option.RENDER_DISTANCE`
   (a `DoubleOption`) is a completely different API from the `SimpleOption`/`OptionInstance` code
   upstream uses today. Only port *semantics* here, never the mixin bodies.
5. **Do not regenerate the Sodium compat layer.** Sodium 0.5/0.6 do not exist for 1.18.2; the `sodium05`/
   `sodium06` source sets and the `sodium06` teleport fix are inapplicable.

### Explicitly out of scope

| Upstream change | Why it does not apply |
| --- | --- |
| `Update to Minecraft 1.19 / 1.19.2 / 1.19.3 / … / 26.2` (14 commits) | The whole point is to stay on 1.18.2 |
| `Switch to mojang mappings`, `Convert code via migrateMappings`, `Rename mixins to match mojang names` | Stay on Yarn |
| `Update to Loom 1.x` / `Update to Gradle 9.x` (8 commits) | Optional; only if the build misbehaves |
| `Add support for Sodium 0.5` (`8509a82`), `Add support for Sodium 0.6.0` (`dd3353c`), `Drop Sodium 0.5` (`c5c7f3d`) | Sodium 0.5/0.6 do not support 1.18.2 |
| `Fix chunks not rendering after teleport with Sodium` (`3cd7baf`) | Sodium 0.6 only |
| `Fix light data being lost when upgrading 1.18 -> 1.19` (`c6f9c20`) | That upgrade path does not exist here (re-evaluate only if the `/bobby upgrade` rewrite needs it) |
| `Fix render distance resetting after restart if above 32` (`07a72bf`) | **Already fixed in 3.1.1** in the 1.18-compatible form (`ModifyArg` on `DoubleOption.setMax`); it was re-fixed upstream only because 1.19 replaced the options API |
| `Fix 1.19.3 server address detection` (`05318e4`) | 1.19.3-only regression — verify, but 1.18.2 resolves the address from the connection directly |
| Release/badge/README/Modrinth-buildscript chores | Cosmetic |

---

## 1. Naming & API translation cheat-sheet

This is where the actual cost lives: upstream code is written against 1.18.2-era **Yarn names** for most
of the range, but against **later Minecraft APIs**.

### Class renames (Yarn 1.18.2 ← upstream Mojang)

| 1.18.2 (Yarn) | upstream (Mojang) |
| --- | --- |
| `ClientChunkManager` | `ClientChunkCache` |
| `WorldChunk` | `LevelChunk` |
| `ChunkManager` | `ChunkSource` |
| `LightingProvider` | `LevelLightEngine` |
| `ChunkLightProvider` | `LightEngine` |
| `BackgroundRenderer` | `FogRenderer` |
| `WorldRenderer` | `LevelRenderer` |
| `ClientPlayNetworkHandler` | `ClientPacketListener` |
| `MinecraftClient` | `Minecraft` |
| `GameOptions` | `Options` |
| `BiomeAccess` | `BiomeManager` |
| `NbtCompound` / `NbtList` / `NbtLongArray` | `CompoundTag` / `ListTag` / `LongArrayTag` |
| `Text` / `TranslatableText` | `Component` |
| `Identifier` | `ResourceLocation` |
| `ClientSettingsC2SPacket` | `ClientInformation` |
| `ChunkNibbleArray` | `DataLayer` |
| `Util.getMeasuringTimeNano()` | `Util.getNanos()` |
| `StorageIoWorker` | `IOWorker` |
| `net.minecraft.world.storage.ChunkSerializer` | `…chunk.storage.ChunkSerializer` |
| `PalettedContainer.PaletteProvider.BLOCK_STATE` | `Strategy.createForBlockStates(...)` |
| `RegistryEntry<Biome>` + `Registry.getCodec(...)` | `Holder<Biome>` + `registry.holderByNameCodec()` |
| `ClientCommandManager` / `CommandManager.literal` | `Commands.literal` |
| `Option.FRAMERATE_LIMIT.getMax()` | `Options.UNLIMITED_FRAMERATE_CUTOFF` |

### Other 1.18.2 constraints

- `fabric-command-api-v1` (not `v2`) — new `/bobby worlds|create|merge` commands keep using
  `ClientCommandManager` / `ClientCommandRegistrationCallback`.
- Mixin config `compatibilityLevel: JAVA_17`; new mixins must be listed in `bobby.mixins.json`
  under their **Yarn** class names.
- 1.18.2 chunk NBT is *not* forward-compatible with the 26.2 `ChunkSerializer` output (section palette
  encoding, biome container, heightmaps, `Lights` handling). Treat the serializer as a rewrite, not a port.
- `MixinConfigPlugin` must keep detecting Sodium **structurally / via mod-id**, never by MC class name
  (MC names are remapped; mod class names are not).

---

## 2. Staged plan

Recommended execution order. Each stage compiles and runs on its own.

### Stage 0 — Set up the branch so cherry-picks resolve properly (done)

`v3.1.1` is a real upstream commit, so the port is built directly on top of it — this makes
`git cherry-pick` do correct 3-way merges instead of hand-typing diffs.

```sh
cd bobby-1.18.2
git checkout -b mc-1.18.2 v3.1.1          # branch for the backport
# work here, cherry-picking commit-by-commit
```

Then (one-off): bump `gradle.properties` `modVersion` to something like `5.2.15.1`, keep
`minecraftVersion = 1.18.2`, keep the Yarn entry, and set the release name to include the MC version
(mirrors upstream's own backport tags such as `v5.0.1.1` / `v5.2.4.1+mc1.21`).

Deliverable: empty diff, `./gradlew build` green.

---

### Stage 1 — Quick wins (independent, low risk) — ~1 day

Each of these is a self-contained patch with no new subsystem.

| # | Commit | What to port | 1.18.2 notes |
| --- | --- | --- | --- |
| 1.1 | `0a9c23b` | Starlight guard in `MixinConfigPlugin` (`hasStarlight` mod check + `shouldApplyMixin` bail-out + `hasClass` via `MixinService`) | Direct port; package prefix `ca.spottedleaf.starlight.` is identical on 1.18.2 |
| 1.2 | `1a7ab5f` | `LastAccessFile` corruption guard (#92) | Direct port |
| 1.3 | `1e7fa2a` | `VisibleChunksTracker` chunk-at-0/0 fix (#205) | Direct port (off-by-one) |
| 1.4 | `53c73b9` | `FakeChunk` guard against invalid block updates (#341) | Direct port |
| 1.5 | `c20b228` | New `ClientSettingsC2SPacketMixin` clamping view distance ≤ 127 sent to server (#135) | Rename mixin/target to `net.minecraft.network.packet.c2s.play.ClientSettingsC2SPacket`; packet field is `viewDistance` (int) |
| 1.6 | `3cfff04` | New `BackgroundRendererMixin` + `WorldRendererMixin` for sky fog with render distance > 32 (#152) | Keep Yarn names; check `BackgroundRenderer.setFogBlack`/`fogY` accessors differ from 1.19 |
| 1.7 | `b0f30bb` | Rename `cleanupOnClose` → `writeable` | Trivial rename |
| 1.8 | `eb3f305` | Better `/bobby upgrade` fallback-world message (#68) | Direct port + `en_us.json` key |
| 1.9 | `60ef5e7` | Unload block entities in fake chunks correctly (#142) | Direct port in `FakeChunkManager` |
| 1.10 | `7877b1e` | Correct cache folder when server name is empty | Direct port |
| 1.11 | `83c03b5` | Reload Bobby chunks **without** modifying the game's view distance (4.0.0) | Semantic port; `FakeChunkManager` only |
| 1.12 | `bbea940` | Don't assume Sodium's renderer is present (#143) | Applies to the 1.18.2 `sodium.SodiumChunkStatusListenerImpl` |
| 1.13 | `df84a5a` | Fix `world` field in `SodiumChunkManagerMixin` (#206) | Direct port |

Deliverable: `./gradlew build` green, manual smoke test in `runClient`.

---

### Stage 2 — Chunk pipeline rewrite (highest risk) — ~3–5 days

The heart of the mod. Upstream split the monolithic `FakeChunkStorage` into
`ChunkSerializer` + `FakeChunkStorage` + `util/LimitedExecutor` + `util/RegionPos`, and moved
serialization off the main thread.

| # | Commit | What to port | 1.18.2 notes |
| --- | --- | --- | --- |
| 2.1 | `c8c3f06` | Extract nested `RegionPos` into `util/RegionPos.java` | Mechanical |
| 2.2 | `c90f1dc` | Simplify `loadTag` return type | Mechanical |
| 2.3 | `48932ee` | Load fake chunks sorted by distance to player (4.0.1) | Mechanical |
| 2.4 | `6c477c3` | Move chunk serialization off the main thread + add `util/LimitedExecutor` | Watch for Java >17 APIs; keep `Util.getMeasuringTimeNano()` |
| 2.5 | **`1c8710a`** | Extract `ChunkSerializer` (468 lines) out of `FakeChunkStorage` | **Rewrite, don't port.** Re-target to 1.18.2 NBT: `NbtCompound`, `NbtList`, `NbtLongArray`, `NbtOps`, `ChunkNibbleArray`, `Heightmap.Type`, `PalettedContainer.PaletteProvider.BLOCK_STATE`, `RegistryEntry<Biome>` codec, `Registry.CODEC`. Keep 3.1.1's serialization shape and adopt only upstream's *fixes* |
| 2.6 | `a949516` | Write `"Status": "full"` into the chunk NBT (#158) | Direct |
| 2.7 | `dc98416` | Fix missing light when a real chunk unloads before its light arrives (#290) | Needs `ClientPlayNetworkHandlerMixin` + `WorldChunkMixin` + `ext/WorldChunkExt`; 1.18.2 packet is `LightUpdateS2CPacket` |
| 2.8 | `9138db9` | Fix newly loaded real chunks flickering black (#290) | Needs `ext/ClientPlayNetworkHandlerExt` |
| 2.9 | `6f9d7be` | Handle server sending out-of-bounds chunks (#313) | In `ClientChunkManagerMixin`; 1.18.2 has no `ChunkHolder`-based bounds check — adapt |
| 2.10 | `e5db1a5`, `d9f74e3` | Executor cleanups (`submit` → `execute`) | Apply last in this stage |

Deliverable: chunks round-trip through the cache, `/bobby upgrade` works, no main-thread stalls on
chunk borders, light is correct after real→fake transitions.

---

### Stage 3 — Dynamic multi-world support (biggest feature) — ~3–4 days

Upstream's v5.1.0/v5.2.0 feature (`cc42d7f` + follow-ups): several logical worlds per physical server,
with fingerprint-based automatic merging of caches.

| # | Commit | What to port | 1.18.2 notes |
| --- | --- | --- | --- |
| 3.1 | `a47a957` | `util/FileSystemUtils` — sanitise world names with illegal characters (#67) | Do this *first*; `Worlds` depends on it |
| 3.2 | **`cc42d7f`** | New `Worlds.java` (1586 lines): per-world cache dirs, region fingerprinting, merging | Largest single item. Most of it is Bobby's own IO/collection code (fastutil, `IOWorker`), but it *does* instantiate a bare `ClientWorld` to deserialize chunks: adapt to the 1.18.2 `ClientWorld` constructor (`ClientWorld.Properties`, `RegistryEntry<DimensionType>`, `RegistryKey`, profiler, `LevelRenderer`/`BlockRenderManager` args) |
| 3.3 | `589e1cc` | Allow `ClientWorld` creation off the main thread (#192) — drop the main-thread assertion, synchronise the static `getFor`/`closeAll` maps | Direct port, needed for mods that build fake worlds off-thread |
| 3.4 | `6a50b8e` | Fix "world contains data from an old version" on first join (#246) | Direct port |
| 3.5 | `6336732` | Dynamic world management on multi-instance servers (#276) | Direct port |
| 3.6 | `8ac8f1b` | Multiworld "Network Protocol Error" (#393) | Direct port |
| 3.7 | `416fd25` | Delete old multi-world caches during cleanup (#399) | Touches `Bobby.java` |
| 3.8 | `1169564` | **Security:** guard against path traversal by a malicious server (5.2.15) | If the Mojang-mapped version is awkward, cherry-pick the Yarn-named backport from tag **`v5.0.1.1`** instead |
| 3.9 | `83fa70d` | Fix thread-unsafe `Worlds` call in `UpgradeCommand` | Direct port |
| 3.10 | new commands | `WorldsCommand`, `CreateWorldCommand`, `MergeWorldsCommand` (+40/34/39 lines) | Translate to `fabric-command-api-v1` (`ClientCommandManager`, `Text.literal`) |
| 3.11 | config | `BobbyConfig.dynamicMultiWorld` flag + `BobbyConfigScreenFactory` toggle + `en_us.json` keys | Direct port |

Deliverable: enabling `dynamicMultiWorld` in the config produces one cache directory per logical world,
merges matching caches, and does not corrupt existing caches; the three new commands are registered.

---

### Stage 4 — Translations & polish — ~½ day

- Copy all `assets/bobby/lang/*.json` from `v5.2.15` wholesale: `ru_ru`, `zh_cn`, `pt_br`, `zh_tw`,
  `tr_tr`, `uk_ua`, `be_by`, `de_de`, `fr_fr`, `ja_jp`, `ko_kr` were added after 3.1.1 (3.1.1 only ships `en_us`).
- Merge new keys into `en_us.json`: `bobby.upgrade.progress`, the new command/error messages,
  the `config.bobby.dynamicMultiWorld` entry.
- `fabric.mod.json`: add `issues` / Modrinth homepage, keep `breaks: sodium <0.3.0` (or tighten to the
  1.18.2-compatible range), keep `fabricloader >=0.11.6`.

---

### Stage 5 — Verification — ~2–3 days

Build/lint gates:

- `./gradlew build` must produce a remapped jar.
- **Verify in a real (production) profile, not just `runClient`.** Loom remaps compiled code but not
  string literals, so any `Class.forName`/name-comparison against a Minecraft class works in dev and
  fails for users. Check `MixinConfigPlugin`'s class probes only ever name *mod* classes.

Manual test matrix:

| Scenario | Expectation |
| --- | --- |
| Server (Paper/vanilla) with view-distance 8, Bobby render distance 32 | Chunks persist and re-render after reconnect |
| Walk out of range, return after > unload delay | Chunks reload from cache |
| `/bobby upgrade` on a populated cache | No light loss, no "old version" warning |
| `dynamicMultiWorld` on a proxy with 2 identically-named worlds | Separate caches, correct merge, no protocol error |
| Server sending out-of-bounds chunks / bad block updates | No crash, chunk ignored |
| Malicious server with world name like `..\..\foo` | Refused, nothing written outside the cache root |
| Sodium 0.4.x installed / not installed / Starlight installed / not installed | No errors in log for absent optional mods |
| Teleport far away, then back | Cached chunks render, no black chunks |
| Chunk at 0/0 on join | Loads from cache |

---

## 3. Risk register

| Risk | Severity | Mitigation |
| --- | --- | --- |
| `ChunkSerializer` rewrite for 1.18.2 NBT | **High** | Do it as a pure extraction *first* (Stage 2.5) with tests comparing against 3.1.1's existing output byte-for-byte, then layer fixes on |
| `Worlds.java` + `ClientWorld` construction on 1.18.2 | **High** | Isolate the single `ClientWorld` factory into one method; if the 1.18.2 constructor proves unusable off-thread, fall back to main-thread construction and only port the cache-management half |
| Java 25 → 17 downgrade of ported code | Medium | Compile as you cherry-pick; `sourceCompatibility = "17"` is already set in 3.1.1's build script |
| Cherry-pick conflicts from the 1.19+ renames | Medium | Cherry-pick oldest-first; resolve using the cheat-sheet in §1 rather than by hand-diffing |
| Post-mojang-mapping commits (`3cd7baf`, `1169564`) | Low | Use the Yarn backport tags (`v5.0.1.1`, `v5.2.4.1+mc1.21`, `v5.2.11.1+mc1.21.11`) as the source instead |
| Regression of existing 1.18.2-only behaviour | Medium | Keep a tag/branch after each stage so any stage can be reverted independently |

---

## 4. Suggested versioning

Mirror upstream's own backport convention:

- `modVersion = 5.2.15.1`, released as `Version 5.2.15.1 for Minecraft 1.18.2`
- CHANGELOG.md: keep `## 5.2.15.1 (MC 1.18.2)` as a new top section listing the backported fixes
  (the release tasks in `build.gradle.kts` read the top section, so the format matters).
