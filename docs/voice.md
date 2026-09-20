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

## What it costs

Recognition runs on the server's CPU (in singleplayer, your own machine):
about a second for a short order on a modern desktop, using up to four
threads while it works. Loaded, the two engines hold a few hundred
megabytes of memory. The native libraries cover Linux x86_64 and arm64,
Windows x86_64 and macOS; they are what makes the mod jar a hundred
megabytes rather than fifteen.

`voice.whisper-model` picks the recognition model: `tiny.en` is faster
and rougher, `small.en` slower and better; `base.en` is the default and
transcribed test orders verbatim. `voice.piper-voice` picks his voice from
the [Piper voices](https://huggingface.co/rhasspy/piper-voices) repository
by id, such as `en_GB-alan-medium`. Changing either fetches the new file
on the next enable.

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
