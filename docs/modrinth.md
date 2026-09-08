<!--
  The Modrinth store page for https://modrinth.com/plugin/jarvis-ai-butler
  This file is the source of truth for that page's description. Edit here,
  then PATCH /v2/project/{id} with it as `body` — otherwise the two drift and
  the published copy silently becomes the only record of what was written.
-->

# Jarvis — Your AI Butler for Minecraft

> *"Very good, sir. I shall see to the excavation."*

**Jarvis** is a fully-featured AI butler NPC for your server — equal parts Alfred and Iron Man's JARVIS. Summon him with a bell, talk to him in plain English, and he mines, guards, farms, fishes, fells trees, fetches your death drops, runs your errands, and delivers a morning briefing on your server's health. Powered by the AI provider of your choice — including **fully local Ollama** on your own hardware, no cloud required.

---

## ✨ What he does

### ⛏️ He actually mines — properly

No teleport-cheating, no instant block deletion. Jarvis pathfinds with Citizens' A\*, breaks blocks with **vanilla timing, arm swings, and crack animations**, and digs real 1×2 tunnels to reach buried ores — staircasing down and back up like a player would.

- `/jarvis mine [ore]` — hunts nearby ores ("jarvis, mine diamonds")
- `/jarvis mine here` — digs a complete **branch mine**: staircase to diamond level, torch-lit main gallery, branch tunnels on a grid, lava pockets sealed with cobblestone. The mine stays lit and walkable for *you* afterwards.

### 🛡️ He guards you

- `/jarvis guard [passive|defensive|aggressive]` — bodyguard with stances and leash-based combat (he never chases a skeleton into the night)
- `/jarvis watch` — night watch: holds a post and clears spawns while you sleep
- `/jarvis patrol` — walks a waypoint circuit you set, armed
- **Threat callouts** — *"Creeper, behind you, sir!"* for hostiles outside your field of view
- **He picks the weapon for the situation, not for his rank** — sword in close, trident underwater, bow at range. With a bow he holds the stand-off band and gives ground when something closes, leads moving targets, and will not loose an arrow through you

### 🧳 He carries and delivers

- Follows you, picks up loot, and hands you food or a spare tool when you're hungry or your pickaxe is about to snap
- Register a chest with `/jarvis chest` — he **delivers his cargo automatically** when his bags fill mid-job
- Died somewhere unfortunate? `/jarvis recover` — he travels to your death point, collects everything, and brings it back

### 🌾 He keeps the grounds

- `/jarvis farm [crop]` / `/jarvis tend` — harvests mature crops with a hoe and **replants from the seeds he collects**; tend mode keeps him working the field
- `/jarvis chop` — fells whole trees (timber!) and replants the right sapling
- `/jarvis fish` — casts from the water's edge with vanilla-ish loot odds
- `/jarvis home` — saves a home point and **escorts you back**, torch-lighting the road
- `/jarvis light` — spawn-proofs an area on a torch grid (mobs spawn at block-light 0; a torch emits 14 and loses 1 per block, so a 12-block grid keeps everything lit)
- `/jarvis dance` — yes, really. He has moves.

### 🖥️ He runs the estate

- `/jarvis report` — the briefing: TPS/MSPT with health coloring, players online, his cargo, pending requests
- `/jarvis duty add 30 Restart at midnight!` — standing broadcasts that **survive restarts**
- Natural-language server admin with a **click-to-confirm safety flow** for anything dangerous (item gives, weather, warps, console commands — all op-gated)
- Non-admin players can *ask* Jarvis for items; admins approve or deny with one click

---

## 💬 Just talk to him

No commands needed — Jarvis parses natural chat through the AI:

> "jarvis, dig a mine here"
> "jarvis, farm the carrots"
> "protect me" · "weapons free" · "stand down"
> "take me home" · "get my stuff back" · "how are things?"

He remembers your recent conversation, so *"do that again"* actually works.

## 🤖 Bring your own AI — or none of the cloud

Tiered routing built for home-labbers:

| Workload | Goes to |
|---|---|
| Chat intents, banter (fast & frequent) | **Ollama first** (your local box), cloud fallback |
| Build planning (rare & heavy) | Cloud first (Claude / OpenAI / Grok / Gemini), Ollama fallback |

Running **only Ollama**? Fully supported — Jarvis enters a reduced mode: constrained JSON parsing that small local models handle reliably, schematic-based building instead of freeform AI designs, and risky console actions locked behind an explicit opt-in. Check routing and provider health any time with `/jarvis ai`.

## 🏗️ Building — he designs, not just pastes

Describe a structure and the AI **writes the build as a small program** that
calls `fill` and `setBlock`, rather than listing every block. A wall is one
call instead of four hundred coordinates, so the model spends its effort on the
design and the plan survives being scaled up. Underground requests carve their
own space first.

Drop `.schem` or `.litematic` files into his library and "jarvis, build me a
house" picks the best match and pastes it (WorldEdit) — still the path used when
you are running local-only. Save, rotate and convert schematics in-game;
`/jarvis build undo` reverts the last one.

## 🎙️ He listens, and he answers

Through [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat), with
speech running wherever you point it — local is the intended setup. Measured
from the moment you stop speaking: ~1.1 s to transcribe, ~0.4 s to work out what
you meant, so he starts moving in about a second and a half. He speaks back
through the NPC when he is beside you, and into your ear when he is away
working.

Three ways to tell him a sentence is for him: hold the whisper key, treat
everything as an order (fine solo), or use a wake word. The wake word is matched
loosely on purpose — speech recognition mangles proper nouns, and "Jarvis" came
back from a real session as *"garibas"* every single time.

## 🎖️ He earns his kit

He starts Probationary with a stone pickaxe and works up to **Without Equal**.
Ranks buy better tools, enchantments and abilities — Fortune, a trident that
comes back, a bow with Power and Flame, the 3×3 tunnelling bore — and they are
earned from what he has actually done for you, per player, persisted. `/jarvis
rank` shows the service record.

## 🧠 He learns from the jobs he has done

He remembers builds that went well and retrieves them when you ask for something
similar, matching on what you asked *and* on where you were standing — "build me
a house" underground at y 12 is not the same request as on a plains surface. He
works out what you actually asked for before searching for a precedent, and when
something goes wrong he says **why**, in a sentence, rather than failing
silently.

## 🌀 He finds nether portals

He notes portals you walk past, remembers them, and leads you to the nearest one
on request. He will also tell you **where any portal comes out on the other
side** — the Overworld runs at eight times the Nether's scale, and the game
links to any portal within 128 blocks of the scaled position, which is why two
portals close together in the Nether end up sharing an exit. That answer needs
no scan and works anywhere.

He leads you *to* a portal and no further: NPCs do not change dimension with
you, and he says so rather than pretending.

## 👀 He notices things

Off by default. Switched on, he remarks on what he can see while standing about
— what you are carrying, how deep you have got, that it has started to thunder.
The lines are written rather than generated, so it costs nothing to run, and
they are keyed by subject so he cannot come back four minutes later with the
same observation in new words. `/jarvis quiet` mutes him.

---

## 📋 Requirements

| | |
|---|---|
| **Server** | Paper / Purpur **1.21.11 – 26.2** (26.3 support when Citizens ships theirs) |
| **Java** | 25 on 26.x servers (21 on 1.21.x) |
| **Required plugin** | [Citizens](https://citizensnpcs.co/ or their latest build at https://ci.citizensnpcs.co/job/citizens2/) **2.0.43+** |
| **Optional** | WorldEdit (schematics), WorldGuard, an AI provider (any one of: Ollama, Claude, OpenAI, Grok, Gemini) |

> **Note:** Citizens supports the latest patch of each Minecraft line — "1.21" support means 1.21.11. Older 1.21.x servers should update.

## 🚀 Quick start

1. Install Citizens, drop `jarvis-x.y.z.jar` in `plugins/`, restart
2. (Optional) Set an AI provider in `plugins/Jarvis/config.yml` — e.g. `ollama.endpoint: "http://your-box:11434"`
3. `/jarvis summon` → look at a chest → `/jarvis chest` → `/jarvis mine here`
4. Watch him work. `/jarvis help` for everything else.

Without any AI configured, all slash commands still work — you only lose natural-language chat and AI schematic picking.

---

Nearly everything is tunable in `config.yml`: mine depth and layout, guard stances and leash range, farming radius, auto-deposit, callouts, celebrations, briefings, and the full AI routing table.

**Source & issues:** [github.com/iamgadgetman/jarvis](https://github.com/iamgadgetman/jarvis) · **[Changelog](https://github.com/iamgadgetman/jarvis/blob/main/CHANGELOG.md)** · **[Docs](https://github.com/iamgadgetman/jarvis/tree/main/docs)**

*Ring the bell. Someone impeccable appears.* 🔔