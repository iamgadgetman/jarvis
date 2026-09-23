# The Bell Menu

Everything Jarvis does has a command, and almost everything has a button.
`/jarvis bell` gives you the controller bell; right-click it to open the menu.
Placed as a block, it stays a controller and opens for whoever rings it. On
Paper you can also right-click the NPC.

---

## The main page

The top rows are the things you do constantly:

| | |
|---|---|
| **Summon** / **Dismiss** | Bring him out, send him away. Dismissing keeps his items |
| **Come here** | Recall him to you |
| **Follow** | Stay close and carry loot |
| **Stop** | Halt whatever he is doing |
| **Loot** | Open what he has collected |
| **Deposit** / **Set deposit chest** | Deliver to your chest, or register the chest you are looking at |
| **Clear loot** | Drop everything he carries, asks first |

The rest of the page opens the sub-pages:

| Page | What is on it |
|---|---|
| **Mining** | Ores, branch mines, shafts |
| **Combat & Guard** | Stances, night watch, patrol |
| **Groundskeeping** | Farm, chop, fish, light the place |
| **Building** | Custom build, and the schematic library |
| **Household** | Home, escort, death drops |
| **Steward** | Briefing, standing duties, AI status, version |
| **Service record** | His rank, what he has earned, what is next. Shown only while progression is on |
| **Settings** | Torches, pickup range, returns |
| **Admin** | Only for admins — see below |

---

## Settings

The page offers torch placement, torch spacing, pickup range, auto-return and
vein mining, each a click.

**Known issue in 0.17.0:** these buttons write `mining.place-torches`,
`torch-spacing`, `pickup-radius`, `auto-return` and `enable-vein-mining` into
`config.yml` — server-wide, not per player — and nothing reads those keys
yet. The change is saved and has no effect. The same goes for the torch
buttons on the Mining page. See **Tuning** in [Mining System](Mining-System)
for the keys that do work.

---

## Admin

Visible only with `jarvis.admin`, or to operators on the mods.

| | |
|---|---|
| **Pending requests** | Item requests awaiting a decision |
| **AI setup** | Providers, keys, models and a connection test |
| **Voice setup** | Engine, gate, speak replies, timing and a test |
| **Reload config** | Re-read `config.yml` |
| **Export dataset** | Dump intent and build pairs as JSONL |

**AI setup** is a row of providers: right-click toggles one on or off,
left-click opens its page, where you set the key or address, pick the model
(chosen from what your Ollama server actually offers, or typed), and run a
test that goes past the routing to the provider itself. Keys typed here are
captured from chat without being shown or logged.

**Voice setup** carries the engine (embedded or a speech server), the gate,
whether he speaks his replies, a timing benchmark and a full status report.
See [Voice](Voice).

Neither page needs a restart. Both write `config.yml` in place, keeping its
comments.
