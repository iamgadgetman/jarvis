# FB-10 — Jarvis MCP server for development

**Lane E · Size M · Branch `fb/10-mcp-dev-server` · Idea from:** the Minecraft MCP servers
(yuniko-software, hibukki) that let Claude Desktop drive a Mineflayer bot, and ArchGPT's
MCP backend mode.

## Goal

A small Node.js MCP (Model Context Protocol — the standard way Claude Code and Claude
Desktop call external tools) server in `tools/jarvis-mcp/` that exposes Jarvis's
in-game actions and status as tools. With it, Claude Code can *test the running server*
during a feature block: summon Jarvis, send him a chat line, read the console log for
the result, and iterate — instead of Gadget relaying every test by hand. This is a
developer tool; it never ships in the plugin jar.

Transport: RCON (the Minecraft remote console protocol; enable in `server.properties`)
for sending commands, and tailing `logs/latest.log` for results. No Java changes unless
the audit finds no console "say-as" command.

Alternative to evaluate first: `amp_restart.py` already talks to AMP. If AMP's API
exposes console send + log read, the MCP server can wrap that instead of RCON and need
no server.properties change. Pick whichever is simpler; document the choice.

## Audit first

1. `docs/commands.md`: every `/jarvis` subcommand available to drive from console.
2. Does the plugin have a console-only command to inject a chat line as a player
   (`/jarvis say-as <player> <text>` or similar)? If not, this block adds one, op/console
   only, gated by `dev.allow-say-as: false` default. That's the single Java change.
3. `server.properties` RCON keys (`enable-rcon`, `rcon.port`, `rcon.password`) or the AMP
   API, per the note above.

## Implementation

`tools/jarvis-mcp/` (TypeScript, `@modelcontextprotocol/sdk`, `rcon-client`):

| Tool | Does |
|---|---|
| `jarvis_status` | Runs `/jarvis status` (or `list` + `tps`), returns text |
| `jarvis_command(args)` | Runs any `/jarvis …` subcommand; refuses anything not starting with `jarvis` |
| `jarvis_say(player, text)` | Runs `/jarvis say-as <player> <text>` so the intent pipeline sees it as chat |
| `server_log(since_seconds, grep?)` | Returns matching lines from `latest.log` |
| `server_command(cmd)` | Any console command; **disabled unless** `JARVIS_MCP_ALLOW_ANY=1` |

- Config via env only: `RCON_HOST`, `RCON_PORT`, `RCON_PASSWORD`, `MC_LOG_PATH`. **No
  hostnames, IPs or passwords in any committed file** — the README uses `192.0.2.10`
  as the example host (TEST-NET), per the public-repo rule.
- `README.md` in the folder: install (`npm i && npm run build`), the
  `.mcp.json` snippet for Claude Code (project-scoped, so it's in the repo and every
  session gets it), and a worked example: "ask Claude Code to summon Jarvis, say 'mine
  10 iron', and read back the OreMiner log lines".
- Add `tools/jarvis-mcp/node_modules` and `dist` to `.gitignore`; add a
  `.mcp.json` at repo root pointing at `node tools/jarvis-mcp/dist/index.js` with env
  values left as `${RCON_HOST}`-style references, never literal.
- Java (only if the audit finds no say-as command): `commands/JarvisCommands.java` adds
  `/jarvis say-as <player> <text…>`, console/op only, enabled by `dev.allow-say-as`.

## Files

`tools/jarvis-mcp/{package.json,tsconfig.json,src/index.ts,README.md}` (new),
`.mcp.json` (new), `.gitignore`, possibly `commands/JarvisCommands.java`,
`config.yml` (`dev:` section), `CHANGELOG.md`, `docs/dev/testing-with-mcp.md` (new).

## Unit tests

Node side, `tools/jarvis-mcp/test/` with `node --test`:
- `jarvis_command` refuses `op me`, accepts `jarvis summon`.
- `server_command` refuses unless env flag set.
- Log tailer returns only lines newer than `since_seconds` and matching `grep`.
- RCON client mocked; no real server in tests.
Java (if say-as added): `CommandServiceTest` — `say-as` from a player sender is refused;
from console with flag off is refused; with flag on routes to `IntentPipeline` as
`Source.CONSOLE` for the named player.

## In-game test (owner)

1. Enable RCON in `server.properties` (or point at AMP), restart. `cd tools/jarvis-mcp &&
   npm i && npm run build`. Open Claude Code in the repo; `/mcp` shows `jarvis-mcp`
   connected.
2. Ask Claude Code: "Using the jarvis tools, check status, summon Jarvis for Gadget, have
   me say 'follow me', and show me the log lines from the last 20 seconds." Expect all
   four to work and the summary to quote the real log.
3. Ask it to run `op Gadget` via `server_command` → refused (flag unset).
4. Use it for one real block test, e.g. FB-01 step 1, and confirm the agent can see the
   absence of an `AI usage` line itself.
5. `git diff` the branch and grep for your real host/IP/password → nothing.

## CHANGELOG line

`### Added` (under a *Developer tooling* heading) — **MCP server for testing.**
`tools/jarvis-mcp` lets Claude Code drive a running server over RCON — status, `/jarvis`
commands, chat-as-player, log tailing — so feature blocks can be exercised in-game from
the terminal. Not part of the plugin jar.
