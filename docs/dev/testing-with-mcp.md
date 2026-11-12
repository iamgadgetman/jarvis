# Testing a feature block with jarvis-mcp

`tools/jarvis-mcp` lets Claude Code drive a running test server instead of a person
relaying each step. Setup is in [its README](../../tools/jarvis-mcp/README.md).

Why RCON and not the AMP API: AMP (the game-server panel some hosts use) can also send
console commands and read the log, but logging into it needs an account, a password and a
one-time code, and the API is specific to AMP. RCON is built into every Minecraft server,
needs one `server.properties` change, and a 100-line client. The MCP server uses RCON.

A block's in-game script maps onto the tools like this:

| Script step | Tool |
|---|---|
| "say X" as a player | `jarvis_say(player, "X")` (needs `dev.allow-say-as: true`) |
| `/jarvis <subcommand>` (player) | `jarvis_say(player, "<subcommand>")`; console-only ones (`ai`, `setup`, `reload`) via `jarvis_command` |
| "expect Y" | `server_log(30, "Y|say-as")`: Jarvis's replies to say-as lines are in the log |
| check a provider was or wasn't called | `server_log(30, "AI usage")` with `ai.log-usage: true` |

What it can't do: anything that needs a real client (moving, clicking a [Confirm] link,
seeing particles or chat colours). For those, use a headless client or a person.
