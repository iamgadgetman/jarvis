#!/usr/bin/env node
// jarvis-mcp: lets Claude Code (or any MCP client) drive a running Jarvis
// test server. Commands go over RCON; results are read from logs/latest.log.
// Configuration is environment only, so nothing private sits in the repo:
//   RCON_HOST, RCON_PORT (default 25575), RCON_PASSWORD, MC_LOG_PATH,
//   JARVIS_MCP_ALLOW_ANY=1 to enable server_command.
import { readFile, stat } from "node:fs/promises";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { Rcon, type CommandRunner } from "./rcon.js";
import { anyCommandAllowed, jarvisOnly, normalise, oneLine, playerName, recentLines } from "./tools.js";

const env = process.env;

function rcon(): CommandRunner {
  if (!env.RCON_HOST || !env.RCON_PASSWORD) {
    throw new Error("set RCON_HOST and RCON_PASSWORD in the MCP server's environment");
  }
  return new Rcon(env.RCON_HOST, Number(env.RCON_PORT || 25575), env.RCON_PASSWORD);
}

let client: CommandRunner | undefined;
async function run(command: string): Promise<string> {
  client ??= rcon();
  try {
    return await client.run(command);
  } catch (e) {
    client.close();
    client = undefined;
    throw e;
  }
}

/** Minecraft colour codes (§a) out of RCON replies. */
const plain = (s: string) => s.replace(/§[0-9a-fk-or]/gi, "");

const text = (t: string) => ({ content: [{ type: "text" as const, text: t || "(no output)" }] });
const fail = (e: unknown) => ({ content: [{ type: "text" as const, text: `Error: ${(e as Error).message}` }], isError: true });

const server = new McpServer({ name: "jarvis-mcp", version: "0.1.0" });

server.registerTool("jarvis_status", {
  description: "Jarvis's AI provider status and the server's player list and TPS.",
  inputSchema: {},
}, async () => {
  try {
    const parts = await Promise.all([run("jarvis ai"), run("list"), run("tps").catch(() => "")]);
    return text(parts.map(plain).filter(Boolean).join("\n"));
  } catch (e) { return fail(e); }
});

server.registerTool("jarvis_command", {
  description: "Run one /jarvis command from the console, e.g. \"jarvis ai test ollama\". "
    + "Anything that doesn't start with jarvis is refused.",
  inputSchema: { command: z.string().describe("The whole command, starting with 'jarvis' (a leading / is fine)") },
}, async ({ command }) => {
  try { return text(plain(await run(jarvisOnly(command)))); } catch (e) { return fail(e); }
});

server.registerTool("jarvis_say", {
  description: "Have an online player say a line to Jarvis (/jarvis say-as). Jarvis's replies are copied to the "
    + "server log as \"[say-as <player>] ...\"; read them with server_log. Needs dev.allow-say-as: true on the server.",
  inputSchema: { player: z.string(), text: z.string() },
}, async ({ player, text: line }) => {
  try {
    return text(plain(await run(`jarvis say-as ${playerName(player)} ${oneLine(line)}`)));
  } catch (e) { return fail(e); }
});

server.registerTool("server_log", {
  description: "Lines from logs/latest.log written in the last since_seconds, optionally filtered by a "
    + "case-insensitive regular expression (e.g. \"Jarvis|say-as\").",
  inputSchema: {
    since_seconds: z.number().int().positive().max(86400).default(30),
    grep: z.string().optional(),
  },
}, async ({ since_seconds, grep }) => {
  try {
    const path = env.MC_LOG_PATH;
    if (!path) throw new Error("set MC_LOG_PATH to the server's logs/latest.log");
    const [log, info] = await Promise.all([readFile(path, "utf8"), stat(path)]);
    const lines = recentLines(log, since_seconds, grep, new Date(), info.mtimeMs);
    return text(lines.join("\n"));
  } catch (e) { return fail(e); }
});

server.registerTool("server_command", {
  description: "Run any console command. Disabled unless JARVIS_MCP_ALLOW_ANY=1 is set for the MCP server.",
  inputSchema: { command: z.string() },
}, async ({ command }) => {
  if (!anyCommandAllowed(env)) {
    return fail(new Error("server_command is disabled; set JARVIS_MCP_ALLOW_ANY=1 to allow any console command"));
  }
  try { return text(plain(await run(oneLine(normalise(command))))); } catch (e) { return fail(e); }
});

await server.connect(new StdioServerTransport());
