# Getting Started with Jarvis

> **Draft (FB-11).** Written from `Commands-and-Permissions.md` and the 0.18.0 changelog.
> Before publishing: run every command below on a test server, fix any that differ, and
> remove this note.

Ten minutes from "who is this?" to a butler that mines for you. Two tracks: **players**
(you joined a server that has Jarvis) and **server owners** (you installed him and need
him to start thinking).

---

## Players: your first ten minutes

### 1. The bell

When you first join, Jarvis hands you a **controller bell** and says hello. Lost it?
`/jarvis bell` gives you another. Right-click the bell to open his menu — every button is
also a command, so use whichever you prefer.

### 2. Summon him

Press **Summon** in the menu, or type:

```
/jarvis summon
```

He appears beside you. Since 0.18.0 you don't strictly need this — any job you give him
brings him out — but it's a good way to meet.

### 3. Talk, don't type commands

Jarvis understands sentences. In normal chat, try:

```
follow me
```

He'll fall in behind you and carry what you pick up. Other things to say: `stop`,
`come here`, `guard me`, `go home`. If a server runs a local AI model, replies can take
a few seconds the first time; you'll see him turn to you and an action-bar line saying
he's thinking.

### 4. Give him a chest

Look at a chest and say `this is my chest` (or `/jarvis chest`). From now on,
`deposit` empties his bags into it.

### 5. Put him to work

```
mine 5 iron
```

He finds exposed ore nearby, digs it, and carries it. Then `stop` and `deposit`. Other
jobs that work the same way:

| Say | He does |
|---|---|
| `chop ten trees` | Fells trees, replants saplings |
| `farm` / `tend the wheat` | Harvests and replants once / stays on as a farmhand |
| `dig me a shaft` | Ladder-lined, torch-lit shaft straight down |
| `go fishing` | Exactly that |
| `light up this area` | Torches on a grid so nothing spawns |
| `guard me` / `attack` | Bodyguard / weapons free |
| `take me home` | Escorts you to your saved home (`/jarvis home set` first) |
| `recover my stuff` | Goes to where you died and brings it back |

### 6. Let him build — and take it back

```
build a small stone hut
```

He designs it, places it block by block, and asks first if it's big. Don't like it?

```
/jarvis undo
```

Everything he placed is gone, exactly as it was before. `/jarvis undo list` shows what
he can still revert.

### 7. The rest

- `/jarvis loot` — see what he's carrying. **Loot** in the menu does the same.
- `/jarvis rank` — he earns better tools and enchantments as he works. Check his record.
- `/jarvis report` — his briefing: server health, players, his cargo, your pending requests.
- `/jarvis quiet` — mute his idle remarks, for you only.
- `/jarvis help` — the full list in game.

Run the interactive version any time with `/jarvis tutorial`. It reacts to what you do,
step by step.

---

## Server owners: make him think in five minutes

Jarvis works out of the box for everything that doesn't need understanding —
`/jarvis mine`, `/jarvis follow`, the bell menu. For sentences in chat he needs an AI.
Three ways to get one, cheapest first. Pick one; you can add others later.

### Option A — free, local: Ollama

Runs on your own machine, no account, no key. Needs a few GB of RAM.

```bash
curl -fsSL https://ollama.com/install.sh | sh     # or your OS's installer
ollama pull mistral
```

Jarvis finds Ollama on `localhost:11434` automatically. In game, `/jarvis setup` should
now say `ollama OK (mistral)`. Say `hello` to Jarvis.

### Option B — free tier, cloud: Gemini

1. Get a key at [aistudio.google.com](https://aistudio.google.com) (free tier is enough
   to start).
2. Either export it before starting the server:
   ```bash
   export GEMINI_API_KEY=your-key-here
   ```
   or put it in `plugins/Jarvis/config.yml`:
   ```yaml
   ai:
     gemini:
       api-key: "env:GEMINI_API_KEY"     # reads the environment variable above
   ```
3. Restart. `/jarvis setup` shows `gemini OK`.

### Option C — Jarvis Cloud: nothing to manage

If you'd rather not run a model or juggle keys:

```
/jarvis cloud activate
```

Jarvis puts a link in chat. Open it, press **Activate** (email optional), and within a
few seconds he writes the key into his own config and starts using it. It's a free
trial first; `/jarvis cloud status` shows your plan and usage, `/jarvis cloud plans`
shows what's available. Nothing about your server or players is sent except a random
install ID.

Bring-your-own-key (A or B) is free forever and sends nothing to Jarvis Cloud. Pick C
for convenience, not because you have to.

### Then

- `/jarvis setup` any time to see which providers are working.
- `/jarvis bell` for your own bell; players get one automatically on first join.
- `config.yml` is documented in place — every key has a comment next to it. The
  [Configuration](Configuration.md) page covers the big knobs.
- Problems: [Troubleshooting](Troubleshooting.md), or the Discord in the README.

---

## What to try next

- **Voice.** With Simple Voice Chat installed, Jarvis listens and talks back. See
  [Voice](Voice.md).
- **Schematics.** Drop `.schem` files in his folder and `/jarvis paste <name>`. See
  [Building System](Building-System.md).
- **Standing duties.** `/jarvis duty add 60 "Deposit check"` and he'll remind you hourly.
- **Portals.** He remembers every nether portal he's seen. `/jarvis portal where` tells
  you where this one comes out.
