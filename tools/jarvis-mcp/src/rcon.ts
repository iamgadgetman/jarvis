// A minimal Source RCON client, the protocol Minecraft's server.properties
// `enable-rcon` turns on. One TCP connection, authenticate once, then send
// commands one at a time. Packets: int32 length, int32 id, int32 type, ASCII
// body, two NUL bytes; all little-endian.
import net from "node:net";

const AUTH = 3;
const EXEC = 2;

export interface CommandRunner {
  run(command: string): Promise<string>;
  close(): void;
}

export function encode(id: number, type: number, body: string): Buffer {
  const text = Buffer.from(body, "utf8");
  const buf = Buffer.alloc(14 + text.length);
  buf.writeInt32LE(10 + text.length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  text.copy(buf, 12);
  return buf; // the last two bytes are already 0
}

/** Pull every complete packet off the front of `buf`; return them and what's left. */
export function decode(buf: Buffer): { packets: { id: number; type: number; body: string }[]; rest: Buffer } {
  const packets = [];
  let off = 0;
  while (buf.length - off >= 4) {
    const len = buf.readInt32LE(off);
    if (buf.length - off - 4 < len) break;
    const id = buf.readInt32LE(off + 4);
    const type = buf.readInt32LE(off + 8);
    const body = buf.toString("utf8", off + 12, off + 4 + len - 2);
    packets.push({ id, type, body });
    off += 4 + len;
  }
  return { packets, rest: buf.subarray(off) };
}

export class Rcon implements CommandRunner {
  private socket?: net.Socket;
  private nextId = 1;
  private pending = new Map<number, (body: string) => void>();
  private buffer: Buffer = Buffer.alloc(0);
  private connecting?: Promise<void>;
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private host: string, private port: number, private password: string, private timeoutMs = 10000) {}

  /** One connection however many callers ask at once. */
  private connect(): Promise<void> {
    if (this.socket && !this.socket.destroyed) return Promise.resolve();
    this.connecting ??= this.open().finally(() => { this.connecting = undefined; });
    return this.connecting;
  }

  private async open(): Promise<void> {
    this.socket = await new Promise<net.Socket>((resolve, reject) => {
      const s = net.connect({ host: this.host, port: this.port }, () => resolve(s));
      s.once("error", reject);
    });
    this.socket.on("data", (chunk: Buffer) => {
      const { packets, rest } = decode(Buffer.concat([this.buffer, chunk]));
      this.buffer = rest;
      for (const p of packets) {
        if (p.id === -1) {
          for (const done of this.pending.values()) done("\u0000AUTH_FAILED");
          this.pending.clear();
          continue;
        }
        const done = this.pending.get(p.id);
        if (done) {
          this.pending.delete(p.id);
          done(p.body);
        }
      }
    });
    this.socket.on("close", () => {
      for (const done of this.pending.values()) done("\u0000CLOSED");
      this.pending.clear();
    });
    const auth = await this.send(AUTH, this.password);
    if (auth === "\u0000AUTH_FAILED") throw new Error("RCON authentication failed (check RCON_PASSWORD)");
  }

  private send(type: number, body: string): Promise<string> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`RCON: no answer in ${this.timeoutMs} ms`));
      }, this.timeoutMs);
      this.pending.set(id, (b) => {
        clearTimeout(timer);
        resolve(b);
      });
      this.socket!.write(encode(id, type, body));
    });
  }

  /** Commands go one at a time, in the order asked, so replies can't cross. */
  run(command: string): Promise<string> {
    const next = this.queue.then(async () => {
      await this.connect();
      const out = await this.send(EXEC, command);
      if (out === "\u0000CLOSED") throw new Error("RCON connection closed");
      return out;
    });
    this.queue = next.catch(() => undefined);
    return next;
  }

  close(): void {
    this.socket?.destroy();
  }
}
