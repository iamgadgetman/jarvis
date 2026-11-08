# FB-07 — Build guardrails: blocked blocks, limits, preview

**Lane D · Size M · Branch `fb/07-build-guardrails` · Idea from:** AIConstructor
(blocked-block list, size limits, WorldGuard, cooldown + daily limits by permission,
particle preview → confirm → place → undo), Shabao (overwrite confirmation).

## Goal

AI-designed builds can't place dangerous blocks, can't exceed a size budget, show a
particle outline the player confirms before anything is placed, and are rationed per
player by permission node. This is the block that makes it safe to run Jarvis on a
public server. Undo is already solved by `UndoService` (0.18.0).

## Audit first

1. `building/BuildingAssistant.java`, `building/ScriptBuildPlanner.java`,
   `building/ShapePlan.java`, `building/UndoService.java`: where a plan becomes block
   placements, where the cleaners (`DoorwayClearer`, `WalkwayClearer`, `HangingTrimmer`,
   `BlockIdFixer`) run, and whether any preview or confirmation already exists
   (`ConfirmationManager` is used elsewhere; find out if builds use it). The policy check
   must run **after** the cleaners and before the first block is placed.
2. `config.yml` `building:` section (WorldGuard is there; see what else).
3. `core/platform/World.java` for a particle API; add one if missing.

## Implementation

- `building/BuildPolicy.java` (platform-free): validates a `ShapePlan` before placement:
  blocked block ids, total block count ≤ `max-blocks`, bounding box ≤ `max-size`,
  distance from player ≤ `max-distance`, and Y within world limits. Returns a list of
  violations with human text ("plan uses 2 TNT; TNT is blocked").
- Preview: when a plan passes, render its outline with particles for `preview-seconds`
  (corners + edges of the bounding box, plus a sparse sample of block positions), send
  the player "Jarvis: <N> blocks, <WxHxD>. Say *yes* to build or *no* to cancel." Use
  `ConfirmationManager` for the yes/no. Placement only after yes; cancel clears particles.
  Builds already paid for by the model are not re-planned on cancel; the plan is dropped.
- Rationing: `building.limits.<permission-group>` → `cooldown-seconds`, `daily-builds`,
  `daily-blocks`. Resolve the player's group by permission node
  `jarvis.build.limit.<name>` (first match wins, `default` otherwise). Count only
  **successful** placements (failed AI calls don't burn quota) — persisted in SQLite so a
  restart doesn't reset it. `jarvis.build.bypass` skips all limits.
- Undo: already solved in 0.18.0 by `UndoService` (persisted, per player, every placement
  path). Nothing to build here — just confirm a confirmed-and-placed build shows up in
  `/jarvis undo list`, and a cancelled preview does not.
- Config:
  ```yaml
  building:
    guardrails:
      blocked-blocks: [tnt, lava, lava_bucket, command_block, chain_command_block,
                       repeating_command_block, structure_block, barrier, bedrock,
                       end_portal_frame, spawner, fire]
      max-blocks: 4000
      max-size: 48            # Longest edge of the bounding box
      max-distance: 64        # From the player
      preview-seconds: 20     # Particle outline + yes/no window
    limits:
      default:  { cooldown-seconds: 120, daily-builds: 5,  daily-blocks: 6000 }
      trusted:  { cooldown-seconds: 30,  daily-builds: 30, daily-blocks: 60000 }
      # Permission jarvis.build.limit.trusted puts a player in "trusted"
  ```

## Files

`building/BuildPolicy.java` (new), `building/BuildQuota.java` (new),
`building/BuildingAssistant.java`, `core/platform/World.java` (+ Paper impl, mod
stubs) for particles, `DatabaseManager.java` (quota table), `config.yml`,
`plugin.yml` (permissions), `CHANGELOG.md`, `docs/configuration.md`.

## Unit tests

`building/BuildPolicyTest.java`:
- Plan with TNT → violation names TNT; clean plan → no violations.
- 4001 blocks → violation; 4000 → ok. Edge 49 → violation.
- Plan 70 blocks away → violation.
`building/BuildQuotaTest.java` (in-memory SQLite, fake clock):
- 5 builds today ok, 6th refused with a message naming the reset time.
- Cooldown: second build 30 s after first refused for `default`, allowed for `trusted`.
- Failed placement doesn't count. Day rollover resets.
`BuildingAssistantTest`: plan goes to preview, nothing placed until confirm; `no` or
timeout places nothing and clears particles; `yes` places and the batch appears in
`UndoService`.

## In-game test (owner)

1. `jarvis build a small stone hut` → particle outline appears, chat asks yes/no. Say
   `no` → nothing placed; `/jarvis undo list` unchanged.
2. Repeat, say `yes` → hut placed. `/jarvis undo` → gone.
3. `jarvis build a tower of TNT` → refused with "TNT is blocked".
4. `jarvis build a 100-block-wide wall` → refused for size.
5. As a default player, build 5 times, 6th refused with reset time. Give yourself
   `jarvis.build.limit.trusted` → allowed.
6. Restart server mid-day → quota count survives.
7. Build inside a WorldGuard region you don't own → still refused (regression check).
8. Run the fish-market fixture (`build-fixtures/fish-market-2.js`) through the preview:
   the policy must not reject a legitimate large build under the defaults.

## CHANGELOG line

`### Added` — **Build guardrails.** AI builds show a particle preview and wait for yes;
dangerous blocks are refused, size and distance are capped, and each permission group
gets a cooldown and daily budget that only successful builds spend. `building.guardrails`
and `building.limits`.
