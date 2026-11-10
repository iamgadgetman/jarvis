# FB-12 — Build polish from the GadgetCraft playtests

**Lane B (build prompt in `ai/AIConnector`) + D (`building/` cleaners) · Size S ·
Branch `fb/12-build-polish` · Depends on:** — · **Source:** Gadget's playtests on the
GadgetCraft Purpur 26.3 server, 2026-11-07/08 (mansion and seaside shanty, Sonnet 5.5
through Jarvis Cloud).

## Already fixed during the playtests (on `release/0.18.0`, for the record)

These were found and fixed while testing 0.18.0. Don't redo them; check they still hold.

| Problem seen | Fix | Commit |
|---|---|---|
| Builds cut off ("Expected , but found eof") | Jarvis Cloud plan caps raised; plugin says "too big for your plan" instead of retrying (`PlanLimitException`) | 8811aec |
| Script builds trimmed to the bottom 5000 blocks | `max-ai-blocks` applies to JSON plans only | 1b0a50c |
| ChestSort Plus scrambled bell-menu icons | Menu redrawn at MONITOR on open, clicks cancelled at LOWEST | 7d94569 (verified in game) |
| Stairs to the mansion's 2nd floor backwards | Prompt states staircase `facing` = direction you walk to climb | 7d94569 (**not yet verified**) |
| `chain` cost a repair round trip | `BlockIdFixer` maps it to `iron_chain` locally | a71b61c (not yet on GadgetCraft) |

Cloud side (repo `jarvis-cloud`, not this block): builds go to Sonnet 5.5 with thinking
off, streamed upstream with a keep-alive downstream.

## Goal

The things still wrong in the shanty, the first build that otherwise looked right:

1. **Front "windows" were solid.** They were lighter wood squares (probably closed
   trapdoor shutters) with no glass; no light got in. Shutters go *beside* a window, never
   *in* it. Every window opening holds glass or a glass pane.
2. **Cobwebs on the front wall.** Decor the player didn't ask for that reads as a
   mistake. No cobwebs unless the request asks for something abandoned, haunted or ruined.
3. **Stairs leading nowhere.** Two stair blocks stacked by the table in a one-storey
   house. A stair block used as a chair stands alone, one high, next to a table, facing
   away from it. A flight of stairs exists only to reach another floor.
4. **Staircase facing (carry-over).** Confirm on a two-storey build that 7d94569 fixed it.

## Audit first

1. `ai/AIConnector.java`: the script build prompt (windows paragraph near "Cut the
   opening in the wall and put the glass in it", the staircase paragraph after the roof
   facing rule). Items 1–3 are prompt rules first.
2. `building/` cleaners (`DoorwayClearer`, `WalkwayClearer`, `HangingTrimmer`): decide if
   any rule should also be a cleaner so it holds even when the model ignores the prompt.
   Likely candidates: a trapdoor that fills a window opening in an outer wall becomes
   glass_pane; a stair column with nothing to reach gets cut to one block.

## Implementation

- Prompt: add the three rules above in the same plain style as the existing ones, each
  with a one-line example.
- Cleaner (only if the audit says the prompt isn't enough): `WindowFiller` — in an outer
  wall, a closed trapdoor or a lighter plank that sits where the wall has a one-block
  opening pattern at eye height becomes `glass_pane`. Keep it conservative; a false
  positive punches a hole in a wall.
- No config keys.

## Unit tests

- Prompt test: the build prompt contains the window, cobweb and chair rules.
- If `WindowFiller` is built: a wall with a trapdoor in a window gap → glass_pane; a
  trapdoor used as a floor hatch or shutter beside glass → unchanged.

## In-game test

1. Deploy a jar that includes a71b61c and this block.
2. `/jarvis build two-storey cottage with a staircase` → stairs climb the right way, the
   top step lands on the upper floor with headroom.
3. `/jarvis build seaside shanty` again → front windows show glass, no cobwebs, no stair
   blocks stacked in the middle of a room.
4. `/jarvis build abandoned shack` → cobwebs allowed.
5. Server log: no "Build script attempt 1 failed: 'chain'".

## As built

- The three rules are in both build prompts: the script prompt (now `AIConnector.SCRIPT_SYSTEM_PROMPT`)
  and the JSON plan prompt (`PLAN_SYSTEM_PROMPT`). Both prompts moved from inline strings to
  constants so `BuildPromptTest` can check them; the text is otherwise unchanged.
- **No `WindowFiller` yet.** Whether the prompt is enough only shows in a real build; the cleaner
  is built only if the in-game shanty still has solid windows.
- a71b61c (`chain` → `iron_chain`) is already on `release/0.18.0` and in every GadgetCraft jar since
  2026-11-09, so step 1's precondition holds.

## Results

Run 2026-11-10 from the headless client (callmegadget) around x -3040, z 3130, Sonnet 5.5 through
Jarvis Cloud; each check read from the script Jarvis stored (`build_experiences.plan`).

| Step | Result |
|---|---|
| 1 deploy | Builds `ec4a5ccf` (`359f192`), then `f0bfc075` (`21830d8`). a71b61c included. |
| 2 two-storey cottage | **First try PARTIAL** (build 8): stairs climb the right way (`facing=north` for a flight rising north) but stop one step short of the upper floor, and both chairs had their backs to the table. `21830d8` lists all four chair placements and gives the step count. **Retest PASS** (build 11, -3041 87 3200): five steps, top one level with the floor, headroom over each; chairs on both floors face away from the table. |
| 3 seaside shanty | **PASS** (build 9): glass_pane in every window, open trapdoor shutters one block outside the wall beside them, no cobwebs, no stacked stairs. (Its chairs had the backwards facing fixed in `21830d8`.) |
| 4 abandoned shack | **PASS** (build 10): four cobwebs in the interior corners. |
| 5 log | **PASS**: no "Build script attempt 1 failed: 'chain'". |

`WindowFiller` not needed: both houses put glass in every opening from the prompt alone.
Test builds left in place for the owner to look at: 8 (cottage, overbuilt by 9), 9 (shanty) at
-3044 75 3127, 10 (shack, on the seabed) at -3004 43 3127, 11 (cottage) at -3041 87 3200.
