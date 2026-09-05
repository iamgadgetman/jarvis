# Troubleshooting, Updating, and Performance

See also: [README](../README.md) • [Configuration](configuration.md)

## Troubleshooting

**"Citizens not found"** — Citizens is required, not optional. Check it is in
`plugins/`, that its version matches your server, and that the console shows it
loading before Jarvis.

**"WorldEdit not found"** — only building and schematic features need WorldEdit.
Everything else works without it. Verify with `/we version`.

**"AI API error"** — work through these in order:

1. Check the key in `plugins/Jarvis/config.yml` — no stray quotes or spaces, and
   it belongs to the provider named in `ai.provider`.
2. Confirm the key is active and has quota in your provider's console.
3. Test connectivity from the server itself:
   ```bash
   read -rsp "Anthropic API key: " ANTHROPIC_API_KEY; echo
   curl https://api.anthropic.com/v1/messages \
     -H "x-api-key: $ANTHROPIC_API_KEY" \
     -H "anthropic-version: 2023-06-01" \
     -H "content-type: application/json" \
     -d '{"model":"claude-opus-5","max_tokens":64,
          "messages":[{"role":"user","content":"test"}]}'
   ```
4. Check the host allows outbound HTTPS — some hosts block it by default.

**Natural language not responding** — confirm `natural-language.enabled: true`,
try the explicit prefix (`jarvis <command>`), and check console for errors.

**Building not working** — verify WorldEdit loaded, that you have build rights
in the area, and try something simple first (`/jarvis build wall`). Script
failures and the repair retry are logged: look for `Build script attempt N
failed:`, which names the exact reason the model was given.

**Freeform builds fall back to the old planner** — the console says GraalJS is
not on the classpath. The engine is fetched at start via `libraries:` in
`plugin.yml`, so a server with no outbound access never gets it. Build with
`mvn -Pshade-graaljs package` to bundle it into the jar instead (~60 MB). This
requires a source checkout and Maven, for example:
```bash
git clone https://github.com/iamgadgetman/jarvis.git
cd jarvis
mvn -Pshade-graaljs package
```

**Script builds refuse to run** — they need a cloud model; a local 7B does not
write usable JavaScript. On an Ollama-only server, use schematics or set
`build.planner: json`.

**"Database init error"** — `plugins/Jarvis/` must be writable. Failing that,
delete `database.db` and restart, and check free disk space.

`/jarvis debug` prints the live provider, model and feature state — start there.

## Updating

1. Back up your config: `cp plugins/Jarvis/config.yml ~/jarvis-config-backup.yml`
2. Stop the server
3. Replace the jar: `rm plugins/jarvis-*.jar && cp Jarvis-<new>.jar plugins/`
4. Start the server

New config keys are added automatically and your existing settings are kept.

## Performance Notes

AI calls are async and never block the main thread. Typical cost:

| Operation | Latency |
|---|---|
| Natural language | ~200–500 ms |
| AI building design | ~1–3 s, then progressive placement |

Block placement is throttled to 50 per tick so large builds do not stall the
server, natural language has a 2-second cooldown per player, and AI caching
costs roughly 50 MB of heap.

On a busy server (50+ players), set `natural-language.require-prefix: true` so
Jarvis only parses chat aimed at him. On a small server, leave it `false` for a
more natural feel.
