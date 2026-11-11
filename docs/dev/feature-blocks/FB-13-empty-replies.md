# FB-13 — Guards for empty model replies

**Lane A (`intent/IntentPipeline`) · Size S · Branch `fb/13-empty-replies` · Depends on:** — ·
**Source:** GadgetCraft playtests 2026-11-10 with qwen2.5:7b on Ollama.

## Problem

Two empty-reply shapes reached players:

1. **Silent reply.** "jarvis what should I do next?" got no answer and no error. The model
   returned `chat` with an empty `response`, and `chat` says nothing beyond its response.
2. **Blank announcement.** "announce that …" produced `broadcast` with an empty `message`.
   Confirm/Cancel offered `Broadcast to all: "?"`.

## Change

In `IntentPipeline.parseAndDispatch`, after the ask-guard picks the final action:

- A public action (`PUBLIC_ACTIONS`: `server_say`, `broadcast`, `announce_all`,
  `schedule_broadcast`, `discord_broadcast`) with a missing or blank `message` takes the text
  from the player's own words ("announce that **X**", "tell everyone **X**", "let everyone know
  **X**"), and Confirm/Cancel shows it as usual. qwen did exactly this on GadgetCraft: it put
  "The market opens at noon." in its spoken reply and left `message` empty. Only when the
  player's words hold nothing either ("announce that") is nothing run or queued: Jarvis asks
  "What would you like me to announce, sir?" and a warning is logged.
- A `chat`/`talk` result whose reply is blank (and wasn't already streamed) gets
  "I lost my train of thought there, sir. Ask me again?" and a warning naming the message.
  The keyword fallback is deliberately **not** used here: it would turn "what's the best way
  to mine?" into a mining order.

No config keys. No platform interface changes, so the mods need no rebuild.

## Tests

`EmptyReplyTest` (fake Ollama): empty and whitespace chat replies say "Ask me again"; a normal
chat reply is unchanged; an asked-for `broadcast` with an empty message and a `server_say`
with no parameters ask what to announce and run nothing; a broadcast with a message still runs;
`blankMessage` helper cases; an empty `message` with "announce that the market opens at noon"
broadcasts the player's words; `messageFromRequest` cases.

## In-game test (GadgetCraft, callmegadget)

The model has to misbehave for 1 and 2 to show, so they are checked by the unit tests and the
server log (`Model returned chat with an empty response`, `Model picked broadcast with no message`).

| # | Do | Expect |
|---|---|---|
| 1 | `jarvis what should I do next?` (a few times) | Always a reply: an answer, or "Ask me again?". Never silence. |
| 2 | `jarvis announce that` (nothing after it) | "What would you like me to announce, sir?", no Confirm prompt, nothing posted. |
| 3 | `jarvis announce that the market opens at noon` | Confirm/Cancel shows the real message; Confirm posts it. |
| 4 | `jarvis hello` | Normal chat reply, no "Ask me again". |

## Results

Run 2026-11-10 with callmegadget, **using `/jarvis <sentence>`** so replies stay private. Plain
`jarvis …` chat is public: every player sees the question.

First jar (`dd7e6f1d`):

| # | Result | Notes |
|---|---|---|
| 1 | PASS | Two asks, two answers. The empty reply did not recur, so the guard didn't fire. |
| 2 | PASS (not via the guard) | "announce that" was answered as chat ("A broadcast at your service, sir."); nothing posted. |
| 3 | **FAIL** | qwen left `message` empty and put the text in its reply; the guard asked "What would you like me to announce?" on a real request. Fixed: the player's words are used. |
| 4 | PASS | Normal reply. |

Second jar (`adab3814`, commit `874c064`):

| # | Result | Notes |
|---|---|---|
| 1 | PASS | Answered (by handing over a diamond sword: the FB-03 "no weapon" warning; see note). |
| 2 | PASS (not via the guard) | qwen gave a sword again instead of a broadcast; nothing posted. |
| 3 | PASS | Confirm showed `Broadcast to all: "The market opens at noon, brave souls!"`; left to expire, nothing posted. |
| 4 | PASS | (first jar) |

The two guard paths (empty chat reply, empty message filled from the player's words) did not
fire live: qwen didn't produce those shapes on this run. They are covered by `EmptyReplyTest`,
which uses the exact reply qwen gave in the first run.

Note, not in scope: for "what should I do next?" qwen ran `give_item` unasked. The ask-guard
(`unaskedAction`) covers public actions, `report` and teleports, not item gifts.
