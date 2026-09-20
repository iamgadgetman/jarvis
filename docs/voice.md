# Voice: the speech server

*[← README](../README.md) · [Features](features.md) · [Commands](commands.md) · [Configuration](configuration.md) · [Troubleshooting](troubleshooting.md)*

---

Jarvis's ears and mouth are two jobs a Minecraft server cannot do by itself:
turning your speech into text (so he knows what you said) and turning his
reply into speech (so you hear him). Both are done by a **speech server**: a
small program that runs anywhere on your network and answers the same HTTP
API as OpenAI's audio endpoints. Jarvis sends it audio and gets text back,
and sends it text and gets audio back. Nothing leaves your network.

`voice.endpoint` in config.yml is that server's address. The default,
`http://127.0.0.1:8000`, means "this same machine, port 8000". If
`/jarvis voice` says the speech server is unreachable, nothing is listening
there yet: either you have not started one, or it is on another machine and
the address needs to say so.

## Running one

Any Linux box with Docker will do, including the machine the game or the
server runs on. [Speaches](https://github.com/speaches-ai/speaches) bundles
faster-whisper (recognition) and Piper (his voice) behind the right API, and
runs fine on a CPU: on the reference box a short order transcribes in about a
second and a reply synthesises in a fraction of one.

```bash
docker run -d --name speaches --restart unless-stopped \
  -p 8000:8000 \
  -v speaches-cache:/home/ubuntu/.cache/huggingface/hub \
  ghcr.io/speaches-ai/speaches:latest-cpu
```

Then pull the two models Jarvis's config names (once; they are cached in the
volume). Run these on the same machine, or replace `localhost` with its
address:

```bash
curl -X POST "http://localhost:8000/v1/models/guillaumekln/faster-whisper-base.en"
curl -X POST "http://localhost:8000/v1/models/speaches-ai/piper-en_GB-alan-medium"
curl "http://localhost:8000/v1/models"       # both should be listed
```

Check the Speaches README if the image tag or model-pull route has changed
since this was written; the two model ids are the ones in `config.yml`
(`voice.stt-model` and `voice.tts-model`), so whatever you pull, keep those
in step.

## Pointing Jarvis at it

If the speech server runs on the same machine as the game or the server,
the default address is already right. Otherwise find the box's LAN address
(`ip -4 addr` on it, or your router's client list) and tell Jarvis, from the
console or in game:

```
/jarvis voice endpoint http://192.168.1.50:8000
/jarvis voice enable
/jarvis voice test
```

or ring the bell: Admin, then Voice setup. The change takes effect at once,
no restart. `/jarvis voice` should now say the speech server is answering.

## Talking to him

Simple Voice Chat has to be installed on the server (the plugin on Paper,
the mod on Fabric and NeoForge) and on your client, and the world has to
have a voice server: a dedicated server always does, a singleplayer world
only once you open it to LAN. Then, with the default gate, hold the voice
chat **whisper** key while you speak. `voice.gate: wake-word` lets you say
"hey jarvis, ..." instead; `always` treats everything you say as an order,
fine when playing alone.

What he heard is echoed in chat (`voice.echo-transcript`), so a mishear is
obvious, and `/jarvis voice` shows the last transcript and when the last
packet arrived. A GPU is not needed; see ROADMAP.md for what one would buy.
