# Jarvis — Claude Code project guide

Jarvis is an AI butler for Minecraft: a Citizens NPC on Paper/Purpur (and the same
core as a Fabric/NeoForge mod) that mines, fights, farms, builds and talks. The LLM
chooses *what* to do; deterministic Java does it. Owner: Gadget (iamgadgetman).

## Where we are

- Branch `release/0.18.0`. Commits are **local only — do not push** until the owner says
  so. Stage named paths; **never `git add -A` or `git add .`**.
- This repo is public on GitHub. Nothing in it may carry private IPs, internal hostnames,
  tokens or keys (see `~/apps/claude/.claude/rules/public-repos.md`). Live-server
  details (host, AMP, restart script) live **outside** the repo in
  `~/apps/claude/jarvis/NEXT-STEPS-builds.md` and `build-fixtures/`.
- Shipped in 0.18.0 so far: Jarvis Cloud activation (`/jarvis cloud …`), `UndoService`
  (one undo for every placement path), auto-summon (`ButlerService.ensurePresent`),
  GraalJS script builds with `DoorwayClearer`, `WalkwayClearer`, `HangingTrimmer`,
  `BlockIdFixer`, NeoForge 26.1/26.2 compat. Open: the ladder-hatch bug (step 1 of
  `NEXT-STEPS-builds.md`).

## Build and test

```bash
# Unit tests — fast, offline, no server. Run after every change. (~238 tests)
cd jarvis-core && mvn -q -o test

# One test class
cd jarvis-core && mvn -q -o test -Dtest=IntentPipelineTest

# Paper plugin jar (lands in jarvis-paper/target/jarvis-paper-<version>.jar)
mvn -q -o package -pl jarvis-paper -am -DskipTests

# Everything, online (first build or after a dependency change)
mvn clean package -DskipTests

# Fabric / NeoForge mods (only when a block touches them)
cd jarvis-fabric && ./gradlew build
cd jarvis-neoforge && ./gradlew build
```

Java 17 (`maven.compiler.release`), JUnit 5, Maven 3.9+. Core tests run real GraalJS
build scripts; fixtures are in `jarvis-core/src/test/resources/build-scripts/`. Fake
platform objects live in `jarvis-core/src/test/java/com/gadgetman/jarvis/core/testing/`
(`Fixture`, `FakePlatform`, `FakeWorld`, `FakeOwner`, …). Use them; never start a
server in a unit test.

Deploy to the live server: back up the running jar, stage the new one in
`plugins/update/`, restart with `~/apps/claude/jarvis/build-fixtures/amp_restart.py`
(refuses if a player is online). Details and credentials are outside the repo.

## Layout

| Module | What it is |
|---|---|
| `jarvis-core` | Platform-free logic: `ai/` (providers, prompts, Cloud activation), `intent/` (chat → action), `commands/` (ActionExecutor, the JSON action vocabulary), `npc/` (OreMiner, Defender, Farmer…), `building/` (planners, cleaners, UndoService), `memory/` (ExperienceMemory, embeddings), `progression/`, `core/platform/` (the interface adapters implement) |
| `jarvis-paper` | Bukkit/Paper adapter, Citizens NPC provider, `config.yml`, `plugin.yml` |
| `jarvis-nav` | Pathfinding |
| `jarvis-vanilla` | Shared mod code (`FakePlayer`, mixins, `McCompat` per MC API) |
| `jarvis-fabric`, `jarvis-neoforge` | Mod adapters (Gradle) |
| `jarvis-voice` | Simple Voice Chat integration |

Design rules already in the code (keep them):

- **Brain/body split.** `AIConnector` returns a JSON action; `ActionExecutor` runs it.
  Never let the model drive movement tick by tick.
- **Threading.** World reads on the server thread, AI calls async, execution back on
  the server thread. `IntentPipeline` is the one road from "player said something" to
  "Jarvis did something" — route new input through it, don't bypass it.
- **Platform-free core.** Nothing in `jarvis-core` imports Bukkit. Need a world
  capability? Add it to `core/platform/`, implement it in Paper, stub it in the mods.
- **Config is documented in place.** `jarvis-paper/src/main/resources/config.yml`
  explains each key next to it. New keys get a comment and a sane default.
- **Reduced mode.** When only Ollama is available, risky actions
  (`RESTRICTED_IN_REDUCED_MODE` in `IntentPipeline`) stay off. Don't widen that set.
- **Every placement records into `UndoService`.** New code that puts blocks in the world
  goes through it.

## Working a feature block

Post-0.18.0 work is organized as **feature blocks** in `docs/dev/feature-blocks/`. One
block = one branch = one testable thing. They ship as **0.18.1, 0.18.2, …** on the 0.18
line, never as 0.19.0. `README.md` there has the list, lanes and
status. The `/feature-block FB-NN` skill runs the standard loop:

1. Read the block's spec. Do its **Audit first** step before writing code — several ideas
   are partly built already.
2. Branch `fb/NN-short-name` off `release/0.18.0` (or `main` once 0.18.0 is merged).
   Touch only the files the spec lists unless the PR explains why.
3. Add or extend the unit tests the spec names. `cd jarvis-core && mvn -q -o test` green.
4. Add the config keys and comments the spec lists.
5. Add a line under the next unreleased **0.18.x** version in `CHANGELOG.md`.
6. Write the in-game test results table into the block file; do **not** mark it `tested` —
   the owner does that after a real-server run.
7. Commit with the block id first: `FB-03: rich world context in prompts`. Local only.

## How the owner works

Gadget is a network/server engineer with limited Java. In chat and commit messages:

- Complete files or clean diffs, never "insert this somewhere."
- Say **what** changed and **why** in two or three sentences; skip Java theory.
- Every hand-off ends with: test command, package command, deploy step, and an in-game
  script (`/jarvis summon`, say X, expect Y).
- If a build breaks, fix it first and say what broke in one line.
- Explain acronyms the first time (SSE, RCON, NDJSON).

## Don'ts

- Don't bump the version in `pom.xml` inside a feature block; releases follow
  `docs/dev/releasing.md`.
- Don't add dependencies without saying so; the plugin jar is shaded.
- Don't add new ways for the model to run console commands. `console_command` with its
  permission gate is the only one.
- Don't commit API keys, `install-id`, or `.claude/settings.local.json` entries that
  contain them. Config examples use `env:VAR_NAME`.
- Don't push.
