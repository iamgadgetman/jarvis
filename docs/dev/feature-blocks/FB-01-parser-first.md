# FB-01 — Parser-first fast path

**Lane A · Size S · Branch `fb/01-parser-first` · Idea from:** nekostulAICompanion (strict
parser, LLM only when it fails), ChatAI moderation (local layer handles 95%).

## Goal

Common commands ("follow me", "stop", "mine", "come here", "go home", "guard") execute
instantly with no AI call. Only sentences the local parser cannot place go to the model.
Today the keyword matcher is a *fallback* when no AI answers; this block promotes a
tightened version of it to the *first* pass.

Why: zero latency for most commands, near-zero token cost, and Jarvis keeps obeying when
every provider is down.

## Audit first

1. Read `intent/IntentPipeline.java` and `intent/ChatTrigger.java`. Find where the keyword
   fallback runs and what phrases it knows.
2. Read `commands/ActionExecutor.java` for the action names and their parameters.
3. Check `config.yml` for any existing `intent:` or keyword section.
Write a five-line summary at the top of the commit message: what the fallback already
handles and what this block changes.

## Implementation

- New class `intent/FastPathParser.java` (platform-free): `Optional<Intent> parse(String
  text)`. Returns an action + parameters only on a **confident** match: exact phrase,
  phrase with a filler prefix ("jarvis", "please", "can you"), or a simple
  `<verb> <N> <item>` pattern for `mine`, `chop`, `request_item`. Anything with a
  conditional, a question mark, "and", or "then" falls through to the AI.
- `IntentPipeline`: run `FastPathParser` first. On a hit, execute and reply with a short
  canned acknowledgement (reuse existing feedback lines). On a miss, continue to the AI
  exactly as today. Keep the existing keyword fallback for the "no provider answered" case.
- Fast-path never produces actions in `RESTRICTED_IN_REDUCED_MODE`.
- Config, new section:
  ```yaml
  intent:
    fast-path:
      enabled: true          # Parse simple commands locally before asking the AI
      log-hits: false        # Log "[fast-path] follow" lines to see what it catches
      extra-phrases:         # Your own aliases, phrase -> action
        "heel": follow
        "chill": stand_down
  ```

## Files

`jarvis-core/.../intent/FastPathParser.java` (new), `intent/IntentPipeline.java`,
`jarvis-paper/src/main/resources/config.yml`, `CHANGELOG.md`.

## Unit tests

`jarvis-core/src/test/.../intent/FastPathParserTest.java`:
- "follow me", "Jarvis, follow me please", "FOLLOW" → `follow`.
- "mine 32 iron" → `mine` with `{item: iron, count: 32}`; "mine" alone → `mine`.
- "follow me and then build a house", "can you follow me?" (question) → empty.
- "give me op" / anything mapping to a restricted action → empty.
- Extra phrase from config maps; unknown text → empty.
Extend `IntentPipelineTest`: with a fast-path hit, `AIConnector` is **not** called
(use the `Fixture` fakes, assert zero AI invocations).

## In-game test (owner)

1. `/jarvis summon`. Say `follow me`. Expect: Jarvis moves within one tick, reply arrives
   instantly, no `AI usage` line in console (turn on `ai.log-usage: true` first).
2. Say `stop`, `guard`, `go home`. Same: instant.
3. Say `mine 10 iron`. Expect: mining starts, no AI call.
4. Say `could you maybe follow me if it's not too much trouble?` Expect: AI call happens
   (console shows usage), Jarvis follows.
5. Set `intent.fast-path.enabled: false`, `/jarvis reload` (or restart). Step 1 now goes
   to the AI.
6. Remove all API keys and stop Ollama. Say `follow me`. Expect: still works.

### Results — 2026-11-08, GadgetCraft (Purpur 26.3), build `f269f2e`

Run from a headless test client with the owner watching. `ai.log-usage` was
off, so "instant" means the reply arrived within the same second as the chat line.

| Step | Said | Result |
|---|---|---|
| 1 | `jarvis follow me` | Instant reply. Followed three 3-block moves, ended ~2 blocks behind. **Two replies** (see below). |
| 2 | `jarvis stop` | Instant reply. Stayed put while the player moved 6 blocks. **Two replies.** |
| 3 | `jarvis get me 8 torches` | Instant. Resolved to `minecraft:torch`, approval request #1 opened. |
| 4 | `jarvis can you follow me and then guard the house?` | Went to the model, reply after 3 s. **Two replies.** |
| 5–6 | — | Not run. |

**Found:** every chat order was answered twice: the pipeline's reply (fast-path ack or the
model's), then `ButlerService`'s own line. Fixed in the next commit (`ButlerService.confirm`,
run under `runAnswered`); `IntentPipelineTest.fastPathHit_answersOnce` covers it. Retest steps 1–2
for one reply each.

## CHANGELOG line

`### Added` — **Fast path for simple commands.** "follow me", "stop", "mine 10 iron" and
the like run instantly without an AI call; only sentences the local parser can't place
go to the model. `intent.fast-path` in config; add your own aliases under `extra-phrases`.
