# Troubleshooting

`/jarvis debug` and `/jarvis ai` answer most questions about what is running
and which provider is being used. `/jarvis voice` does the same for voice.

---

## It will not load

**"Citizens not found"** (Paper) — Citizens is a hard dependency. Install it
from [ci.citizensnpcs.co](https://ci.citizensnpcs.co/job/citizens2/) and
restart. Citizens supports the latest patch of each Minecraft line, so "1.21"
support means 1.21.11; older 1.21.x servers should update.

**"Unsupported class file major version" or the jar is ignored** — a Java
mismatch. Paper on 1.21.x wants Java 21; every 26.x server and both mods want
Java 25. Check with `java -version`.

**Two Jarvis jars loaded** — the Paper file is now
`jarvis-paper-<version>.jar`. Delete any older `jarvis-<version>.jar` from
`plugins/`.

**The mod does not appear** — Fabric needs Fabric API alongside it. Both mods
need Minecraft 26.3.

---

## He does not understand chat

Run `/jarvis ai`. It shows which providers are enabled, their models, and
whether any is on cooldown after failing.

- **No provider configured** — bell menu, **Admin → AI setup**, or
  `/jarvis ai key <provider> <key>`.
- **A key that does not work** — `/jarvis ai test <provider>` sends one small
  request straight to it, past the routing, so the error is about the key
  rather than the fallback chain.
- **Ollama unreachable** — `/jarvis ai endpoint ollama http://your-box:11434`,
  and check the port is open from the game server. A failed provider goes on
  an exponential cooldown up to five minutes.
- **He answers only sometimes** — `natural-language.require-prefix` may be on,
  in which case he wants his name first. `cooldown-ms` also rate-limits chat
  parsing.

## Builds fail or come out wrong

- **"Failed to generate build plan"** — usually the model's reply was cut
  short. Build planning uses the heavy tier with a long timeout
  (`ai.heavy-timeout-seconds`, 240 by default); a small local model may still
  struggle, so try a cloud provider for building.
- **Something is missing from the build** — `/jarvis build undo` reverts it.
  Unknown block ids are dropped and named in one warning line in the log.
- **No schematic commands** — that needs WorldEdit, and is Paper only.

## Voice

**He does not hear you** — `/jarvis voice` reports every link: whether Simple
Voice Chat took the plugin, whether its voice server is up, whether voice is
enabled, the gate in force, when the last packet arrived, and whether the
engine is ready.

**"A singleplayer world has none until it is opened to LAN"** — press Escape,
**Open to LAN**, or type `/publish`. Each time you open the world.

**"Speech models could not be fetched"** — the server cannot reach
huggingface.co. The log names the three files and where to put them; or set
`voice.models-source` to a mirror. See [Voice](Voice).

**"libgomp.so.1: cannot open shared object file"** — the image is missing a
system library. The jar carries libgomp for Linux and falls back to it on its
own; if the error persists the message names the package
(`apt install libgomp1`). On Alpine (musl) the bundled libraries cannot load
at all; use a glibc-based image.

**He hears you but takes seconds to answer** — `/jarvis voice` shows which
stage took the time. "Hearing" is the recogniser: run `/jarvis voice bench`
and set the thread count it names. "Understanding" is the AI call, which is
usually the answer: a smaller Ollama model, or Claude.

**He was working and stopped hearing you** — if `voice.engine` is `server` and
that server went away, he now says so in chat. `/jarvis voice engine embedded`
needs no server at all.

## Performance

- **Ore scans and portal sweeps** run off the main thread on chunk snapshots
  and should cost no TPS. If they do, `mining.debug: true` logs every step.
- **Busy servers** — `natural-language.require-prefix: true` so he only parses
  chat aimed at him.
- **What builds cost** — `ai.log-usage: true` prints token counts per call,
  tagged with tier and provider.
- **He cannot see into unloaded chunks**, and no plugin can without forcing
  terrain to load. A sweep reaches about as far as you can see, which is why
  he says "none nearby" rather than "there are none".

## Getting the file right

The three release files and what each needs are in
[Installation & Setup](Installation-and-Setup). If a jar refuses to load, the
usual cause is the wrong one for that server.

---

## Reporting a bug

Open an [issue](https://github.com/iamgadgetman/jarvis/issues) with:

- the output of `/jarvis version` and `/jarvis debug`
- the platform: Paper, Fabric or NeoForge, and the Minecraft version
- what you asked him to do and what happened
- the relevant lines from the server log
