# Jarvis Minecraft Assistant

Jarvis is a personal AI assistant for Minecraft implemented as a plugin. The current branch includes mining/navigation fixes so Jarvis can walk, climb, dig, and collect ores for you. You can also send natural-language prompts to your configured AI provider to ask questions or generate build plans.

## Prerequisites
- Java 17 JDK
- Maven 3.9+
- A Purpur/Spigot-compatible server with the **Citizens** and **WorldEdit** plugins installed (they satisfy the provided dependencies).

## Build the updated plugin
1. From the repository root, run `mvn -DskipTests package`. This produces `target/jarvis-0.0.1.jar`.
2. If downloads are blocked in your environment, mirror the Purpur and Citizens repositories or build where outbound Maven access is allowed.

> The JAR is **not** precompiled in source control. You still need to run Maven yourself (or use GitHub Actions/Codespaces) to produce the updated file that goes into your server.

## Deploy to your server
1. Stop the Minecraft server.
2. Copy `target/jarvis-0.0.1.jar` into the server's `plugins/` directory, replacing any older `jarvis` JAR.
3. Start the server. Citizens should spawn Jarvis as before; use your existing commands to have him mine.

## Verifying the mining fix
- After deploying, command Jarvis to mine an ore vein. He should now navigate toward ores (digging/climbing as needed), break them, and keep drops in his inventory instead of idling or disconnecting.

If you still see issues, enable debug logging in your server console and share the stack traces and server logs for further investigation.

## Debugging and logs
- Toggle verbose logging and a plugin-side debug log file with `/jarvis debug on` (requires `jarvis.admin` or console). Logs write to `plugins/Jarvis/debug.log` by default.
- Turn debug off with `/jarvis debug off`.
- Reload config (including debug and API settings) with `/jarvis reload`.

## AI setup and usage
Jarvis can call multiple AI providers for building plans or general questions:
- **OpenAI** (default): configure under `ai.openai.api-key` and `ai.openai.model`.
- **Grok (xAI)**: set `ai.provider: grok`, and configure `ai.grok.api-key` and `ai.grok.model`.
- **Gemini**: set `ai.provider: gemini`, and configure `ai.gemini.api-key` and `ai.gemini.model`.

The plugin also accepts `OPENAI_API_KEY`, `GROK_API_KEY`, or `GEMINI_API_KEY` environment variables if the config values are empty.

### Commands that use AI
- `/jarvis ask <question>`: send a free-form prompt to the configured provider and return the reply in chat.
- The existing building routines call the AI when you request structure plans.

### Why use AI here?
- **Hands-free help**: Ask quick how-tos, recipe reminders, or strategy tips without leaving the game.
- **Planning assistance**: Generate rough build outlines or mining routes the NPC can follow.
- **Adaptive automation**: Combine AI suggestions with Jarvis’s movement/mining so he can act on context-aware instructions you provide.

### Ideas to get more value
- Save frequently used prompts (e.g., “best Y-level for diamonds in 1.20”) and call them via `/jarvis ask`.
- Use Gemini’s long-context models for bigger build descriptions, or Grok/OpenAI for faster replies depending on latency and quotas.
- Enable debug while experimenting to capture the AI requests and NPC navigation decisions in `debug.log` for troubleshooting.

## Getting the code with GitHub Desktop
1. Install [GitHub Desktop](https://desktop.github.com/) and sign in.
2. Click **File → Clone Repository** and paste your repo URL.
3. Choose a local folder and click **Clone**. The source appears on disk and in GitHub Desktop.
4. After pulling future updates, build with `mvn -DskipTests package` and copy `target/jarvis-0.0.1.jar` into your server's `plugins/` folder.
5. Commit/push your own changes from GitHub Desktop when you're ready.
