# FB-02 — "Thinking" feedback while the model works

**Lane A · Size S · Branch `fb/02-thinking-feedback` · Depends on:** nothing (FB-01 first
in the same lane to avoid conflicts in `IntentPipeline`). **Idea from:** ArchGPT (idle
crouch/arm-swing animation + loading bubble), AI_NPC (💭 while thinking).

## Goal

From the moment a player's sentence is sent to the AI until the reply lands, the player
can *see* Jarvis is working: the NPC turns toward the player and does a small idle
animation, and an action-bar line shows "Jarvis is thinking…". Jarvis keeps doing his
current task meanwhile (the pipeline is already async; this block only adds the
feedback). Removes the #1 immersion complaint found in the research: companions that
look frozen.

## Audit first

1. In `IntentPipeline`, find where the async AI call starts and where the reply is
   delivered to the `Responder`.
2. Check `core/platform/Butler.java` for what animation/look calls exist (`lookAt`,
   `swingArm`, `sneak`?) and `core/platform/Owner.java` / `Ui.java` for an action-bar
   or title call. List what's missing — those become platform interface additions.
3. Check `npc/provider/CitizensButler.java` for how animations reach Citizens.

## Implementation

- `core/platform/Butler`: add `void lookAt(Owner player)` and `void playThinking(boolean on)`
  if absent. Paper impl: Citizens `NPC.faceLocation` + a repeating sneak/unsneak every
  ~20 ticks and an arm swing every ~60 while `on`. Fabric/NeoForge impls: look + swing
  (sneak optional) — keep them compiling, minimal.
- `core/platform/Owner`: add `void actionBar(String line)` if absent; Paper impl via
  `Player.sendActionBar`.
- `IntentPipeline`: when the AI branch starts, call `butler.lookAt(player)`,
  `butler.playThinking(true)`, start a repeating action-bar task ("Jarvis is thinking…",
  dots cycling). On reply or timeout, stop both and clear the bar. Fast-path hits
  (FB-01) skip all of this.
- Config:
  ```yaml
  intent:
    thinking-feedback:
      enabled: true
      action-bar: true         # "Jarvis is thinking…" above the hotbar
      animate: true            # NPC looks at you and fidgets while waiting
      text: "Jarvis is thinking"
  ```

## As built (deviations)

- **No sneak.** `Butler` has no crouch call, and adding one meant a new interface method in three
  implementations plus Fabric/NeoForge rebuilds. The fidget is a look plus an arm swing every ~3 s,
  using the existing `lookAt` and `swing`. The owner agreed to this on 2026-11-09.
- **No platform changes.** `Owner.actionBar` already existed (via `Audience`), so the block is
  core-only: new `intent/ThinkingFeedback.java`, hooked in `IntentPipeline`. The
  "playThinking/actionBar" fakes the spec mentions weren't needed; tests read the existing
  `FakeOwner.actionBars` and `FakeButlers.State.swings`.
- **Safety cutoff:** feedback ends by itself after 90 s even if no reply or failure comes back.

## Files

`core/platform/Butler.java`, `core/platform/Owner.java`, `platform/PaperOwner.java`,
`npc/provider/CitizensButler.java`, the Fabric/NeoForge butler impls (stub if needed),
`intent/IntentPipeline.java`, `config.yml`, `CHANGELOG.md`.

## Unit tests

Extend `FakeOwner`/`FakeButlers` in `core/testing/` to record `actionBar` and
`playThinking` calls. In `IntentPipelineTest`:
- AI path: `playThinking(true)` before the AI call, `playThinking(false)` after the reply;
  action bar received at least one line and was cleared.
- AI timeout/failure path: feedback is still turned off (no stuck animation).
- Fast-path hit: no thinking calls at all.
- `enabled: false`: no thinking calls.

## In-game test (owner)

1. Use a slow model on purpose (Ollama 7B, or set `light-timeout-seconds: 30`). Say
   something the fast path won't catch: `what should I do next?`
2. Expect within one tick: Jarvis turns to face you, starts fidgeting, action bar reads
   "Jarvis is thinking…" with cycling dots.
3. When the reply arrives: animation stops, action bar clears, reply prints.
4. Kill the network to the provider and repeat. Expect: after the timeout the animation
   stops and the usual failure message appears — never a Jarvis stuck crouching.
5. `animate: false` → only the action bar. `enabled: false` → nothing.

## CHANGELOG line

`### Added` — **Jarvis shows he's thinking.** While a sentence is with the AI he turns to
you, fidgets, and an action-bar line reads "Jarvis is thinking…". Off with
`intent.thinking-feedback.enabled: false`.
