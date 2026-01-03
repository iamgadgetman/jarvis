# Jarvis Minecraft Assistant

Jarvis is a personal AI assistant for Minecraft implemented as a plugin. The current branch includes mining/navigation fixes so Jarvis can walk, climb, dig, and collect ores for you.

## Prerequisites
- Java 17 JDK
- Maven 3.9+
- A Purpur/Spigot-compatible server with the **Citizens** and **WorldEdit** plugins installed (they satisfy the provided dependencies).

## Build the updated plugin
1. From the repository root, run `mvn -DskipTests package`. This produces `target/jarvis-0.0.1.jar`.
2. If downloads are blocked in your environment, mirror the Purpur and Citizens repositories or build where outbound Maven access is allowed.

## Deploy to your server
1. Stop the Minecraft server.
2. Copy `target/jarvis-0.0.1.jar` into the server's `plugins/` directory, replacing any older `jarvis` JAR.
3. Start the server. Citizens should spawn Jarvis as before; use your existing commands to have him mine.

## Verifying the mining fix
- After deploying, command Jarvis to mine an ore vein. He should now navigate toward ores (digging/climbing as needed), break them, and keep drops in his inventory instead of idling or disconnecting.

If you still see issues, enable debug logging in your server console and share the stack traces and server logs for further investigation.
