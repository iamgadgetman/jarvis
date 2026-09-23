# Voice

Talk to Jarvis and he answers out loud. Speech recognition
([whisper.cpp](https://github.com/ggerganov/whisper.cpp)) and his voice
([Piper](https://github.com/rhasspy/piper)) run **inside the server**. There
is no container to set up and no service to point at.

**You need [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)**
— the plugin on Paper, the mod on Fabric and NeoForge — and nothing else.

---

## Turning it on

1. Install Simple Voice Chat.
2. `/jarvis bell` → **Admin → Voice setup → Voice on**, or `/jarvis voice enable`.
3. The two model files, about 200 MB, are fetched into `models/` under the
   data folder. Progress goes to the log and to `/jarvis voice`. Once only.
4. Say "hey jarvis, ..." and give him an order. That is the `wake-word` gate
   the shipped config starts with; see below for the others.

`/jarvis voice` reports every link of the chain: whether voice chat took the
plugin, whether its voice server is up, the gate, when the last packet came
in, the last transcript, whether the engine is ready, and how long the last
order took.

> **Singleplayer:** a world has no voice server until you open it to LAN.
> Escape → **Open to LAN**, or `/publish` in chat. Each time you open the world.

---

## How he knows you are talking to him

`voice.gate`, in the menu or the config:

- **wake-word** (the default in the shipped config) — say "hey jarvis, ..."
  and the rest is the order. Everything is transcribed, but only sentences
  that open with a wake phrase are acted on.
- **whisper** — hold the voice chat whisper key. Costs nothing and never
  mistakes a conversation with another player for an order.
- **always** — everything you say is an order. Fine playing alone.

The wake word is matched loosely on purpose. Recognisers mangle proper nouns:
"Jarvis" came back from a real session as *"garibas"* every single time, so an
exact match rejected every order given. `voice.wake-words` takes several
phrases, the multi-word ones being the reliable ones, and `wake-fuzz` sets how
much mangling to forgive.

---

## When he is slow to answer

Three things happen after you stop speaking, and `/jarvis voice` reports how
long each took for the last order:

```
Last order took: hearing 380 ms (3.0 s of speech), understanding 27 ms, speaking 77 ms
```

- **hearing** — transcription. Well under a second on a desktop CPU. If it is
  not, run **`/jarvis voice bench`** (or **Time his hearing** on the Voice
  setup page): Piper says a fixed sentence, whisper transcribes it at several
  thread counts, and the report names the fastest and says whether the
  difference is worth taking cores from the game. `/jarvis voice threads <n>`
  keeps a count in `voice.whisper-threads` (0, the default, means four, or
  half the CPUs on a small machine). A smaller `voice.whisper-model` is the
  next lever: `base.en` is the default, `base.en-q5_1` a quantised variant of
  it, `tiny.en` the fastest of all.
- **understanding** — the AI call, the same one chat uses. This is usually the
  whole wait when there is one. A smaller Ollama model or Claude brings it
  down; short direct orders ("come", "follow me", "stop") skip the model
  entirely.
- **speaking** — synthesis, a few hundred milliseconds.

`voice.debug: true` logs the same numbers as they happen. The
`voice.silence-ms` pause that ends a sentence (700 ms) is part of the wait
too; shortening it saves that much, at the cost of splitting a pause for
breath into two orders.

---

## Where his voice comes from

Standing beside you he speaks through his own body, so the voice is positional
and falls off with distance like any player's. Away working, he speaks into
your ear instead, like a radio. His own lines are spoken; mechanical feedback
("Torch spacing: 9") stays in chat. `voice.speak-replies` turns the talking
off and keeps the listening.

---

## What it costs

Recognition runs on the server's CPU — in singleplayer, your own machine —
on four threads by default. Loaded, the two engines hold a few hundred
megabytes of memory. The native libraries cover Linux x86_64 and arm64,
Windows x86_64 and macOS, and are what make every jar large: about 86 MB for
the Paper plugin and about 100 MB for each mod. If voice is never turned on
they are never loaded.

`voice.whisper-model` picks the recognition model, `voice.piper-voice` his
voice, by id from the [Piper voices](https://huggingface.co/rhasspy/piper-voices)
repository. Changing either fetches the new file on the next enable.

---

## What the server needs installed

Nothing, normally. whisper's math library on Linux wants `libgomp`, the GNU
OpenMP runtime, which slim container images leave out; the jar carries a copy
and uses it when the system has none. If the log still names a library that
"cannot open shared object file", it also names the package that provides it
(`apt install libgomp1`, `dnf install libgomp`).

An **Alpine (musl)** image cannot load these libraries at all. Use a
glibc-based image.

## When the server cannot reach huggingface.co

The models come from huggingface.co. A server with filtered egress logs "this
server cannot reach huggingface.co ..." and then names the three files, their
URLs and the folder to drop them in. Three ways round it:

- **Fetch them elsewhere** and put them in `models/` under the data folder:
  `ggml-base.en.bin`, `en_GB-alan-medium.onnx` and `en_GB-alan-medium.onnx.json`
  for the defaults. Toggle voice off and on and they are found.
- **A mirror.** `voice.models-source` replaces `https://huggingface.co` with
  anything serving the same paths.
- **A proxy.** The JVM's `-Dhttps.proxyHost` and `-Dhttps.proxyPort` are used.

---

## Running speech elsewhere

If you would rather keep the work off the game host, `voice.engine: server`
sends it to an OpenAI-compatible speech server
(`/v1/audio/transcriptions` and `/v1/audio/speech`):

```
/jarvis voice engine server
/jarvis voice endpoint http://192.0.2.10:8000
```

The embedded engine is the default and needs none of that. If a server is
configured and stops answering, Jarvis says so in chat rather than leaving
your order unanswered.
