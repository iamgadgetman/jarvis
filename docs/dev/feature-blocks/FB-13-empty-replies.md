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
  `schedule_broadcast`, `discord_broadcast`) with a missing or blank `message` is not run or
  queued. Jarvis replies "What would you like me to announce, sir?" and a warning is logged.
- A `chat`/`talk` result whose reply is blank (and wasn't already streamed) gets
  "I lost my train of thought there, sir. Ask me again?" and a warning naming the message.
  The keyword fallback is deliberately **not** used here: it would turn "what's the best way
  to mine?" into a mining order.

No config keys. No platform interface changes, so the mods need no rebuild.

## Tests

`EmptyReplyTest` (fake Ollama): empty and whitespace chat replies say "Ask me again"; a normal
chat reply is unchanged; an asked-for `broadcast` with an empty message and a `server_say`
with no parameters ask what to announce and run nothing; a broadcast with a message still runs;
`blankMessage` helper cases.

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

_Not run yet._
