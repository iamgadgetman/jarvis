// The parts of the tools that don't need a server: what may be run, and which
// log lines to hand back. Kept apart from index.ts so `node --test` can check
// them with no RCON and no MCP client.

/** Strip a leading slash and spaces; "/jarvis summon " -> "jarvis summon". */
export function normalise(command: string): string {
  return command.trim().replace(/^\/+/, "").trim();
}

/** `jarvis_command` runs only `/jarvis …`; returns the command, or throws with the reason. */
export function jarvisOnly(command: string): string {
  // Refuse anything that tries to chain a second command.
  if (/[\r\n]/.test(command)) throw new Error("one command at a time");
  const cmd = normalise(command);
  if (!/^jarvis(\s+\S.*)?$/i.test(cmd)) throw new Error(`only /jarvis commands, not ${JSON.stringify(cmd)}`);
  return cmd;
}

/** `server_command` is off unless JARVIS_MCP_ALLOW_ANY=1 in the server's environment. */
export function anyCommandAllowed(env: NodeJS.ProcessEnv): boolean {
  return env.JARVIS_MCP_ALLOW_ANY === "1";
}

/** A player name Minecraft would accept: 3-16 of A-Z, a-z, 0-9, _. */
export function playerName(name: string): string {
  const n = name.trim();
  if (!/^[A-Za-z0-9_]{3,16}$/.test(n)) throw new Error(`not a player name: ${JSON.stringify(name)}`);
  return n;
}

/** One line of text for say-as: no newlines (each would be a new console command). */
export function oneLine(text: string): string {
  const t = text.replace(/[\r\n]+/g, " ").trim();
  if (!t) throw new Error("nothing to say");
  return t;
}

const STAMP = /^\[(\d{2}):(\d{2}):(\d{2})\]/;

/**
 * Lines of latest.log from the last `sinceSeconds`, optionally only those
 * matching `grep` (a regular expression, case-insensitive). The log stamps only
 * the time of day, in the server's own time zone, which needn't be ours (a
 * remote server's log, mounted or copied). So the stamps are only compared with
 * each other: the newest stamped line was written at `writtenAt` (the file's
 * modification time), and every other line is that much older than it, going
 * back over midnight when a stamp jumps forward. Lines without a stamp (stack
 * traces) take the stamp of the line above them.
 */
export function recentLines(log: string, sinceSeconds: number, grep: string | undefined, now: Date,
                            writtenAt: number = now.getTime(), maxLines = 400): string[] {
  const cutoff = now.getTime() - sinceSeconds * 1000;
  const pattern = grep ? new RegExp(grep, "i") : undefined;
  const lines = log.split(/\r?\n/).filter(Boolean);
  // Seconds of the day for each line, carried down to unstamped lines.
  const secs: (number | undefined)[] = [];
  let carry: number | undefined;
  for (const line of lines) {
    const m = STAMP.exec(line);
    if (m) carry = Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]);
    secs.push(carry);
  }
  const out: string[] = [];
  let newest: number | undefined;
  let prev: number | undefined;
  let days = 0;
  for (let i = lines.length - 1; i >= 0; i--) {
    const sec = secs[i];
    if (sec === undefined) continue;          // above the first stamp: no time to give it
    newest ??= sec;
    if (prev !== undefined && sec > prev) days++;   // going back past midnight
    prev = sec;
    const at = writtenAt - ((newest - sec) + days * 86400) * 1000;
    if (at < cutoff) break;
    if (pattern && !pattern.test(lines[i])) continue;
    out.push(lines[i]);
  }
  out.reverse();
  return out.length > maxLines ? out.slice(out.length - maxLines) : out;
}
