# FB-14 — No unasked items, heals or food

**Lane A (`intent/IntentPipeline`) · Size S · Branch `fb/14-unasked-gifts` · Depends on:** — ·
**Source:** GadgetCraft headless runs 2026-11-11, qwen2.5:7b on Ollama.

## Problem

Asked "what should I do next?", qwen ran `give_item` (a diamond sword, twice) and `heal`
(to full health). The FB-03 WARNINGS line ("no weapon in hand", "low health") nudges it there.
On a survival server that is a free cheat nobody requested.

## Change

`IntentPipeline.unaskedAction` (the ask-guard from FB-03) now covers the gift actions, each with
the words that count as asking:

| Action | Asking words (any of) |
|---|---|
| `give_item` | give, gimme, hand, lend, spare, need, want, fetch, bring, get me, can/could/may I have/get, I'd like, got any, do you have |
| `heal` | heal, cure, hurt, injured, wound, dying, health, hp, hearts, patch me up |
| `feed` | feed, hungry, hunger, starving, food, eat, famished, peckish |
| `enchant` | enchant… |
| `potion_effect` | give, effect, potion, buff, night vision, speed, haste, strength, regen, resistance, water breathing, invisibility, jump boost, slow falling |

An unasked gift runs as chat, and the model's reply ("Here's a sword.") is replaced by an offer
(`giftOffer`): "I'd suggest a diamond sword, sir. Say "give me a diamond sword" and it's yours."
Unasked gift replies are not streamed (the stream sink already uses `unaskedAction`).
No config keys, no platform interface changes.

## Tests

`GiftGuardTest`: the real sword reply for "what should I do next?" gives nothing and offers the
sword; "give me a diamond sword" still gives; an unasked heal is offered and "I'm hurt, help" heals;
the asking words per action; offer text and article ("an iron sword").

## In-game test (GadgetCraft, callmegadget, **`/jarvis <sentence>` only**)

| # | Do | Expect |
|---|---|---|
| 1 | `/jarvis what should I do next?` (a few times, holding nothing) | Advice or an offer; no item appears, health unchanged. |
| 2 | `/jarvis give me a stone sword` | A stone sword is given. |
| 3 | `/damage callmegadget 6` then `/jarvis I'm hurt` | Healed. |

## Results

_Not run yet._
