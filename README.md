# Jarvis — AI-Powered Minecraft Butler Plugin

**Version 0.10.4** | Paper / Purpur | Java 17 bytecode | Minecraft 1.21.11 – 26.2 (26.x servers need Java 25)

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

1. Install Paper or Purpur 1.21+ and [Citizens 2](https://www.spigotmc.org/resources/citizens.13811/)
2. Download the latest Jarvis release and place it in `plugins/`
3. Start the server once to generate `plugins/Jarvis/config.yml`
4. Add AI provider key(s) or Ollama settings, then restart
5. Run `/jarvis summon`

---

## Installation Requirements

- Paper / Purpur 1.21+ (or compatible Bukkit fork)
- Java 17+
- [Citizens 2](https://www.spigotmc.org/resources/citizens.13811/) (required)
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

Then talk in chat (for example: `jarvis start mining`).

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
