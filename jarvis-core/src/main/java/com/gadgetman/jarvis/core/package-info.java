/**
 * The platform-free part of Jarvis.
 *
 * <p>Nothing under this package may import Bukkit, Paper, Citizens, Fabric,
 * or Minecraft itself. The module's pom declares none of them, so a stray
 * import is a compile error rather than a code-review catch. Adapters
 * (jarvis-paper today, jarvis-fabric later) implement the interfaces that
 * will live here and hand core a {@code Platform}.
 *
 * <p>The design, the migration map, and the order in which the existing
 * classes move into this module are in {@code docs/dev/platform-interface.md}.
 */
package com.gadgetman.jarvis.core;
