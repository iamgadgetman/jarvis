# FB-04 — Per-player conversation memory

**Lane C · Size M · Branch `fb/04-conversation-memory` · Idea from:** Player2 AI NPC v1.4
(episodic memory across sessions), AI_NPC (per-player memory), ReactiveNPC ("she
remembers").

## Goal

Jarvis remembers what each player said and did with him across restarts. "Remember
that ravine we found?" works. Implementation: a rolling per-player transcript in SQLite
(the DB is already there via `DatabaseManager`), summarized by the light-tier model when
it grows past a threshold, with the summary plus the last few turns injected into the
system prompt. Separate from `ExperienceMemory`, which remembers *build outcomes*.

## Audit first

1. `DatabaseManager.java`: schema setup pattern, how tables are created/migrated, SQLite
   vs MySQL handling. `UndoService` (0.18.0) is the newest example of a persisted table —
   copy its approach.
2. `memory/ExperienceMemory.java`: async pattern (all DB off main thread, hot cache in
   memory). Copy that pattern; don't couple to it.
3. `ai/AIConnector.java`: where the system prompt is assembled and whether any message
   history is sent today (a grep found none).
4. `ai/AiSettings` for the light-tier routing used for summaries.

## Implementation

- Table `conversation_turns(id, player_uuid, ts, role, text)` and
  `conversation_summaries(player_uuid, summary, turns_covered, updated_ts)`.
- New `memory/ConversationMemory.java`: `record(player, role, text)` (async write,
  in-memory ring buffer of last N turns per online player), `contextFor(player)` →
  "Earlier with this player: <summary>\nRecent:\n- …" (last `recent-turns`),
  `summarizeIfNeeded(player)` runs on the light tier when unsummarized turns exceed
  `summarize-after`, replacing the summary and marking turns covered. Prune raw turns
  older than `keep-days`.
- Hook: `IntentPipeline` records both the player line and Jarvis's reply (not the
  mechanical feedback). `AIConnector` appends `contextFor(player)` after the world
  context (FB-03) — if FB-03 isn't merged yet, append where the system prompt ends.
- Also record notable **events** as memory lines through the existing event bus
  (`core/platform/events/`): deaths, portal use, big finds (OreMiner reporting diamonds).
  One line each, same table, role `event`.
- Player commands: `/jarvis memory` (shows the summary), `/jarvis memory forget`
  (wipes that player's rows; confirm via `ConfirmationManager`).
- Config:
  ```yaml
  memory:
    conversation:
      enabled: true
      recent-turns: 8          # Verbatim turns sent with each request
      summarize-after: 30      # Unsummarized turns before the light model condenses them
      keep-days: 90            # Raw turns older than this are pruned (summary stays)
      record-events: true      # Deaths, portals, notable finds become memory lines
  ```

## Files

`memory/ConversationMemory.java` (new), `DatabaseManager.java`, `intent/IntentPipeline.java`,
`ai/AIConnector.java`, `commands/JarvisCommands.java`, `config.yml`, `CHANGELOG.md`,
`docs/commands.md`, `docs/configuration.md`.

## Unit tests

`memory/ConversationMemoryTest.java` with an in-memory SQLite (`jdbc:sqlite::memory:`)
and `FakeScheduler`:
- Record 5 turns → `contextFor` lists all 5 in order, no summary.
- Record 31 turns with `summarize-after: 30` → summarizer invoked once with the 31 turns;
  afterwards `contextFor` has the summary + last 8.
- Summarizer failure (fake AI throws) → old summary kept, turns stay unsummarized, no
  exception escapes.
- `forget(player)` → both tables empty for that player, other players untouched.
- Prune: turns dated `keep-days + 1` ago are removed; summary remains.
- `enabled: false` → `contextFor` returns empty, nothing written.

## In-game test (owner)

1. Say `my base is at the big oak by the river`. Then `/jarvis memory` → the line is there.
2. Restart the server. Say `where did I say my base was?` → Jarvis answers from memory.
3. Die once. `/jarvis memory` shows an event line about the death.
4. Have a second player chat with Jarvis; their `/jarvis memory` doesn't show your lines.
5. Talk 30+ turns (or set `summarize-after: 5`). `/jarvis memory` shows a summary
   paragraph instead of raw lines; console shows one LIGHT-tier usage line.
6. `/jarvis memory forget` → confirm → `/jarvis memory` is empty.

## CHANGELOG line

`### Added` — **Jarvis remembers.** Conversations and notable events are kept per player
in the database, condensed by the light model as they grow, and brought into every
reply. `/jarvis memory` shows what he knows; `/jarvis memory forget` wipes it. Settings
under `memory.conversation`.
