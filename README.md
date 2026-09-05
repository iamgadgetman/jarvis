# Jarvis — AI-Powered Minecraft Butler Plugin

**Version 0.10.4** | Paper / Purpur | Java 17 bytecode | Minecraft 1.21+ (tested on 1.21.11–1.21.26; run the Java version required by your server build, and many 26.x servers use Java 25)

Jarvis is a Minecraft AI butler plugin that adds a Citizens NPC companion for chat, mining, building, farming, guarding, and server management.

---

## Highlights

- Natural-language assistant in chat and via `/jarvis ...`
- Multi-provider AI routing (Claude, OpenAI, Grok, Gemini, Ollama)
- Companion NPC with combat, utility, and inventory behavior
- AI/scripted building plus schematic pasting and undo
- Admin automation with safety confirmations

---

## Quick Start

1. Install Paper or Purpur for Minecraft 1.21+ (tested on 1.21.11–1.21.26).
2. Run the server with the Java version required by your Paper/Purpur build (many Minecraft 1.21.26.x builds use Java 25).
3. Add [Citizens 2](https://www.spigotmc.org/resources/citizens.13811/) to `plugins/` (required). Add [WorldEdit](https://enginehub.org/worldedit/) if you want schematic pasting or other WorldEdit-dependent build features.
4. Download **`Jarvis-&lt;version&gt;.jar`** from the [latest release](https://github.com/iamgadgetman/jarvis/releases/latest) and place it in `plugins/` with your other plugin JARs.
5. Start the server once to generate `plugins/Jarvis/config.yml`.
6. Add AI provider key(s) or Ollama settings, then restart.
7. Run `/jarvis summon`.

---

## Installation Requirements

- Paper / Purpur for Minecraft 1.21+ (tested on 1.21.11–1.21.26)
- Java 17+ runtime for Jarvis itself (Jarvis is Java 17 bytecode)
- If your Paper/Purpur build requires Java 25 (common on 1.21.26.x), run the server on Java 25
- [Citizens 2 on Spigot](https://www.spigotmc.org/resources/citizens.13811/) or [Citizens 2 Jenkins build](https://ci.citizensnpcs.co/job/citizens2/) (required)
- [WorldEdit](https://enginehub.org/worldedit/) (optional, for schematic/build features)
- At least one AI provider key (or local Ollama)
- Optional embedding model for experience memory: `ollama pull nomic-embed-text`

---

## Documentation

- [Detailed Features](docs/features.md)
- [Command Reference & Examples](docs/commands.md)
- [Configuration Reference](docs/configuration.md)
- [Troubleshooting, Updating, and Performance](docs/troubleshooting.md)
- [Changelog](CHANGELOG.md)

---

## Common First Commands

```bash
/jarvis bell
/jarvis summon
/jarvis debug
/jarvis loot
```

Then talk in chat (for example: `jarvis start mining`). If `natural-language.require-prefix` is `true`, start with the configured prefix; see [Configuration Reference](docs/configuration.md) and [Command Reference](docs/commands.md).

---

## Building from Source

```bash
git clone https://github.com/iamgadgetman/jarvis.git
cd jarvis
mvn clean package -DskipTests
# Output: target/jarvis-<version>.jar
```

Requires Citizens and WorldEdit JARs on the Maven classpath as configured in `pom.xml`.

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

*Built with Citizens, WorldEdit, Adventure API, and a lot of caffeine.*
