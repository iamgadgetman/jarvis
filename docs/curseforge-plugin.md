<!--
  The CurseForge page for the Paper plugin:
  https://www.curseforge.com/minecraft/bukkit-plugins/jarvis-ai-butler
  (project 1691977, also on dev.bukkit.org). Takes jarvis-paper-<version>.jar
  only; the Fabric and NeoForge jars go to the mods project, described in
  docs/curseforge-mod.md.

  This file is the source of truth for the page's description. CurseForge has
  no API for descriptions: paste it in the authors console, Description, with
  the editor set to Markdown. Pasting Markdown into the WYSIWYG editor is what
  left the old page showing raw # and ** marks.

  Summary (the one-liner under the title):
    An AI butler NPC that mines, guards, farms, builds and talks - local Ollama or any cloud AI.
-->

# Jarvis — Your AI Butler for Minecraft

> *"Very good, sir. I shall see to the excavation."*

**Jarvis** is an AI butler for your server — equal parts Alfred and Iron Man's JARVIS. Summon him with a bell, talk to him in plain English, and he mines, guards, farms, fishes, fells trees, builds, fetches your death drops, runs your errands, and delivers a morning briefing on your server's health. Powered by the AI provider of your choice — including **fully local Ollama** on your own hardware, no cloud required.

This is the **Paper / Purpur plugin**. Running Fabric or NeoForge? The same butler is available as a mod: **[Jarvis for Fabric & NeoForge](https://www.curseforge.com/minecraft/mc-mods/jarvis-ai-butler)**.

---

## 🆕 New in 0.17.0

- **Set up the AI from the bell menu** — Admin → AI setup: switch providers on and off, enter keys, pick an Ollama model from a list, test the connection.
- **Voice runs inside your server** — speech recognition and his voice run in the game server itself through Simple Voice Chat. Nothing else to install.
- **Builds come out whole, and furnished** — doors with both halves, closed roofs, and a bed, chest and crafting table in every house.
- **The file is now `jarvis-paper-<version>.jar`.** Upgrading from 0.16? Delete the old `Jarvis-0.16.0.jar` from `plugins/`.

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
- **He picks the weapon for the situation** — sword in close, trident underwater, bow at range. With a bow he keeps his distance, leads moving targets, and will not loose an arrow through you

### 🧳 He carries and delivers

- Follows you, picks up loot, and hands you food or a spare tool when you're hungry or your pickaxe is about to snap
- Register a chest with `/jarvis chest` — he **delivers his cargo automatically** when his bags fill mid-job
- Died somewhere unfortunate? `/jarvis recover` — he travels to your death point, collects everything, and brings it back

### 🌾 He keeps the grounds

- `/jarvis farm [crop]` / `/jarvis tend` — harvests mature crops and **replants from the seeds he collects**
- `/jarvis chop` — fells whole trees and replants the right sapling
- `/jarvis fish` — casts from the water's edge
- `/jarvis home` — saves a home point and **escorts you back**, torch-lighting the road
- `/jarvis portal` — leads you to the nearest nether portal he has seen, and tells you where any portal comes out on the other side
- `/jarvis light` — spawn-proofs an area on a torch grid
- `/jarvis dance` — yes, really. He has moves.

### 🖥️ He runs the estate

- `/jarvis report` — the briefing: TPS/MSPT, players online, his cargo, pending requests
- `/jarvis duty add 30 Restart at midnight!` — standing broadcasts that **survive restarts**
- Natural-language server admin with a **click-to-confirm safety flow** for anything dangerous (item gives, weather, warps, console commands — all op-gated)
- Non-admin players can *ask* Jarvis for items; admins approve or deny with one click

---

## 💬 Just talk to him

No commands needed — Jarvis understands natural chat through the AI:

> "jarvis, dig a mine here" · "jarvis, farm the carrots" · "protect me" · "take me home" · "get my stuff back" · "how are things?"

He remembers your recent conversation, so *"do that again"* actually works.

## 🤖 Bring your own AI — or keep it all local

| Workload | Goes to |
|---|---|
| Chat and banter (fast & frequent) | **Ollama first** (your own machine), cloud fallback |
| Build planning (rare & heavy) | Cloud first (Claude / OpenAI / Grok / Gemini), Ollama fallback |

Running **only Ollama**? Fully supported — Jarvis switches to a reduced mode that small local models handle reliably, and locks risky console actions behind an explicit opt-in. Set it all up from **Admin → AI setup** in the bell menu, or `/jarvis ai`.

## 🏗️ He designs, not just pastes

Describe a structure — `/jarvis build a small stone cottage with a wooden roof`, or **Building → Custom build** in the bell menu — and the AI designs it and he builds it block by block. Walls come out whole, doors have both halves, pitched roofs close their gables, and houses come **furnished**: bed, chest, crafting table, light. `/jarvis build undo` reverts the last one.

Drop `.schem`, `.schematic` or `.litematic` files into his library and "jarvis, build me a house" picks the best match and pastes it — the path used when you run local-only. With **WorldEdit** installed you can also save and rotate schematics in-game.

## 🎙️ He listens, and he answers

With [Simple Voice Chat](https://www.curseforge.com/minecraft/bukkit-plugins/simple-voice-chat), you can talk to him out loud. Speech recognition (whisper.cpp) and his voice (Piper) run **inside the server** — no extra service to host. He speaks from where he stands when he's beside you, and into your ear when he's away working. Turn it on from **Admin → Voice setup** or `/jarvis voice enable`.

## 🎖️ He earns his kit

He starts Hired, with an iron pickaxe, and works up to **Without Equal**. Ranks unlock better tools, enchantments and abilities — Fortune, a returning trident, a bow with Power and Flame, a 3×3 tunnelling bore — earned from what he has actually done for you. `/jarvis rank` shows his service record.

---

## 📋 Requirements

- **Paper or Purpur 1.21.11 – 26.2** (Java 21; Java 25 on 26.x)
- **[Citizens](https://www.curseforge.com/minecraft/bukkit-plugins/citizens) 2.0.43+** — required
- Optional: **WorldEdit** (saving and rotating schematics), **Simple Voice Chat** (voice), and an AI provider — any one of Ollama, Claude, OpenAI, Grok or Gemini

Without any AI configured, every slash command still works — you only lose natural-language chat and freeform building.

### About the file size

The jar is about **86 MB** because it carries the speech engine for Linux, Windows and macOS, so voice works with nothing else installed. It is only loaded if you turn voice on. Two more downloads happen on first use, into your server's own folders:

- **On first start**, Paper fetches the JavaScript engine the build planner uses (about 60 MB, into `libraries/`). If it can't, freeform building still works with the simpler planner.
- **When you first turn voice on**, the two voice models (about 200 MB) are downloaded, with progress shown in the log and in `/jarvis voice`.

## 🚀 Quick start

1. Put `jarvis-paper-<version>.jar` and Citizens in `plugins/`, restart
2. `/jarvis bell`, ring it, **Admin → AI setup**, pick a provider
3. `/jarvis summon` → look at a chest → `/jarvis chest` → `/jarvis mine here`
4. Watch him work. `/jarvis help` for everything else.

---

**Wiki:** [github.com/iamgadgetman/jarvis/wiki](https://github.com/iamgadgetman/jarvis/wiki) · **Source & issues:** [github.com/iamgadgetman/jarvis](https://github.com/iamgadgetman/jarvis) · **[Changelog](https://github.com/iamgadgetman/jarvis/blob/main/CHANGELOG.md)**

*Ring the bell. Someone impeccable appears.* 🔔
