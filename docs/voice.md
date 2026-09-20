# Voice

*[← README](../README.md) · [Features](features.md) · [Commands](commands.md) · [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md)*

---

Jarvis hears you through Simple Voice Chat and answers out loud. The
listening (whisper.cpp) and the talking (Piper) happen inside the server
process: there is nothing else to install and nothing to point him at.

## Turning it on

1. Install Simple Voice Chat on the server (the plugin on Paper, the mod on
   Fabric or NeoForge) and on your client.
2. Ring the bell: Admin, Voice setup, and switch Voice on. Or `/jarvis voice
   enable` from the console.
3. The first time, he fetches two model files into `config/jarvis/models`
   (`plugins/Jarvis/models` on Paper): a whisper.cpp model, about 150 MB,
   and a Piper voice, about 60 MB. The log counts the download up in tens
   of percent, and `/jarvis voice` says "models downloading" until they are
   here. Once only; they stay.
4. Hold the voice chat **whisper** key and give him an order.

`/jarvis voice` reports every link of the chain: whether voice chat took
the plugin and its voice server is up, the gate, when the last packet came
in, the last transcript, and whether the speech engine is ready.

## What the server needs installed

Nothing, on Linux x86_64 and arm64, Windows x86_64 and macOS: the native
libraries are in the jar. whisper's math library on Linux wants `libgomp`,
the GNU OpenMP runtime, which slim container images leave out; the jar
carries a copy and uses it when the system has none. If the log still
says a library "cannot open shared object file", it names the library and
the package that provides it. An Alpine (musl) image cannot load these
libraries at all; use a glibc-based image such as the Ubuntu ones.

## When the server cannot reach huggingface.co

The models come from huggingface.co. A server with filtered egress logs
"Speech models could not be fetched: this server cannot reach
huggingface.co ..." and then names the three files and their URLs. Three
ways round it:

- **Fetch them elsewhere and drop them in.** `ggml-base.en.bin`,
  `en_GB-alan-medium.onnx` and `en_GB-alan-medium.onnx.json` (for the default
  model and voice) go in `plugins/Jarvis/models/` on Paper or
  `config/jarvis/models/` on the mods. Turn voice off and on, or
  `/jarvis reload`, and they are found.
- **A mirror.** `voice.models-source` replaces `https://huggingface.co`;
  anything that serves the same paths will do, including a directory on an
  internal web server laid out the same way.
- **A proxy.** The JVM's `-Dhttps.proxyHost` and `-Dhttps.proxyPort` are
  honoured.

## What it costs

Recognition runs on the server's CPU (in singleplayer, your own machine):
well under a second for a short order on a modern desktop, on four threads
while it works (`voice.whisper-threads` changes that; see below). Loaded,
the two engines hold a few hundred megabytes of memory. The native libraries cover Linux x86_64 and arm64,
Windows x86_64 and macOS; they are what makes the mod jar a hundred
megabytes rather than fifteen.

`voice.whisper-model` picks the recognition model: `tiny.en` is faster
and rougher, `small.en` slower and better; `base.en` is the default and
transcribed test orders verbatim. `voice.piper-voice` picks his voice from
the [Piper voices](https://huggingface.co/rhasspy/piper-voices) repository
by id, such as `en_GB-alan-medium`. Changing either fetches the new file
on the next enable.

## When he is slow to answer

Three things happen after you stop speaking, and `/jarvis voice` reports
how long each took for the last order:

- **hearing**: transcription. Under a second on a desktop CPU. When it is
  not, `/jarvis voice bench` (or "Time his hearing" on the Voice setup
  page) has Piper say a sentence and times whisper on it at several thread
  counts, then names the fastest; `/jarvis voice threads <n>` keeps it.
  More threads is not faster on a machine that is also running the game:
  the recogniser's workers spin while they wait for each other, and once
  they outnumber the free cores the whole pass crawls. The bench line also
  says which vector instructions the build is using. Beyond threads, a
  smaller `voice.whisper-model` is the lever: `tiny.en` is the fastest,
  and `base.en-q5_1` is `base.en` quantised, smaller and usually quicker
  on a CPU.
- **understanding**: the AI call, the same one chat uses. This is usually
  the whole wait when there is one: a cloud model answers in one to three
  seconds, a large local model on a busy machine can take ten. A smaller
  Ollama model, or Claude, brings it down; the short direct orders
  ("come", "follow me", "stop") skip the model and answer at once.
- **speaking**: synthesis, a few hundred milliseconds for a sentence.

`voice.debug: true` logs the same three numbers as they happen. The
`voice.silence-ms` pause that ends a sentence (700 ms) is also part of the
wait; shortening it saves that much, at the cost of splitting a pause for
breath into two orders.

## Where a voice comes from

Standing beside you he speaks through his own body, so his voice is
positional and falls off with distance like any player's. Away working, he
speaks into your ear instead, like a radio. His own lines are spoken;
mechanical feedback ("Torch spacing: 9") stays in chat. `voice.speak-replies`
turns the talking off and keeps the listening.

## How he knows you are talking to him

`voice.gate`, in the menu or the config:

- **whisper** (default): hold the voice chat whisper key. Costs nothing and
  never mistakes a conversation with another player for an order.
- **always**: everything you say is an order. Fine playing alone.
- **wake-word**: say "hey jarvis, ..." and the rest is the order. The name
  is matched loosely because recognisers mangle it; `voice.wake-words` and
  `voice.wake-fuzz` tune that.

What he heard is echoed in chat (`voice.echo-transcript`), so a mishear
is obvious.

## Using a speech server instead

If you would rather keep the work off the game host, `voice.engine: server`
points him at any OpenAI-compatible speech server (`/v1/audio/transcriptions`
and `/v1/audio/speech`), such as a [Speaches](https://github.com/speaches-ai/speaches)
container, at `voice.endpoint`. Set both from the menu (Engine, then Speech
server) or with `/jarvis voice engine server` and `/jarvis voice endpoint
http://host:8000`. `voice.stt-model` and `voice.tts-model` name the models
that server should use.

## Singleplayer

A singleplayer world has no voice server until you open it to LAN
(Escape, Open to LAN); Simple Voice Chat starts one then. A dedicated server
always has one.
