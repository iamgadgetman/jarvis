# jarvis-mcp — drive a test server from Claude Code

A small [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server, so
Claude Code can exercise a running Jarvis test server during a feature block:
summon him, say a line as a player, run `/jarvis` commands and read the log back.
It talks to the server over **RCON** (Minecraft's remote console) and reads
`logs/latest.log` from disk. A developer tool only; it is not in the plugin jar.

| Tool | Does |
|---|---|
| `jarvis_status` | `/jarvis ai`, `list` and `tps` from the console |
| `jarvis_command(command)` | One `/jarvis …` command; anything else is refused |
| `jarvis_say(player, text)` | `/jarvis say-as <player> <text>`: the player says it to Jarvis; his replies land in the log as `[say-as <player>] …` |
| `server_log(since_seconds, grep?)` | Lines of `latest.log` from the last N seconds, optionally filtered |
| `server_command(command)` | Any console command; **off unless** `JARVIS_MCP_ALLOW_ANY=1` |

## Set up the test server

1. In `server.properties`: `enable-rcon=true`, `rcon.port=25575`, and a long random
   `rcon.password`. Keep the RCON port off the internet; RCON is plain text.
2. In `plugins/Jarvis/config.yml`: `dev.allow-say-as: true`. Only on a test server:
   with it on, anyone with console or RCON access can speak as any online player.
3. Restart the server.

## Build and connect

```bash
cd tools/jarvis-mcp
npm install
npm run build
npm test          # unit tests, no server needed
```

The repo's `.mcp.json` registers the server for Claude Code in this project. It reads
everything from your environment, so nothing private is committed. Before starting
Claude Code:

```bash
export RCON_HOST=192.0.2.10                       # your test server (example address)
export RCON_PORT=25575
export RCON_PASSWORD='the rcon.password you set'
export MC_LOG_PATH=/path/to/server/logs/latest.log # a local path; mount or sync it if the server is remote
# export JARVIS_MCP_ALLOW_ANY=1                    # only if you want server_command
claude
```

`/mcp` in Claude Code should list `jarvis-mcp` as connected.

## Worked example

> Using the jarvis tools: check status, summon Jarvis for Gadget, have Gadget say
> "mine 10 iron", then show me the log lines from the last 30 seconds that mention
> Jarvis or OreMiner.

Claude Code calls `jarvis_status`, `jarvis_say("Gadget", "summon")`,
`jarvis_say("Gadget", "mine 10 iron")`, then `server_log(30, "Jarvis|OreMiner|say-as")`,
and reports what the log actually says, including Jarvis's reply lines. (Most `/jarvis`
subcommands are player-only, so for things a player does, use `jarvis_say`;
`jarvis_command` is for console ones such as `jarvis ai`, `jarvis setup`, `jarvis reload`.)

`server_log` needs the log file on the machine running Claude Code. When the server
is elsewhere, mount its `logs/` folder or point `MC_LOG_PATH` at a synced copy.
