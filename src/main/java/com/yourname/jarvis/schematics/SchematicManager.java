package com.yourname.jarvis.schematics;

import com.yourname.jarvis.Jarvis;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.util.*;

/**
 * SchematicManager - Manages schematic files using WorldEdit
 *
 * Supports WorldEdit .schem/.schematic files for proper building.
 * Falls back to JSON schematics if WorldEdit is not available.
 */
public class SchematicManager {

    private final Jarvis plugin;
    private final File schematicFolder;
    private final Map<String, SchematicInfo> availableSchematics = new HashMap<>();

    // WorldEdit integration
    private boolean worldEditEnabled = false;
    private Plugin worldEditPlugin = null;

    // Configuration
    private boolean allowDownloads = true;
    private int maxDownloadSize = 10; // MB
    private boolean pasteAir = false;

    public SchematicManager(Jarvis plugin) {
        this.plugin = plugin;
        this.schematicFolder = new File(plugin.getDataFolder(), "schematics");
        loadConfig();
        initializeFolder();
        initializeWorldEdit();
        scanFolder();
        plugin.getLogger().info("Schematic manager initialized with " + availableSchematics.size() + " schematics" +
            (worldEditEnabled ? " (WorldEdit enabled)" : " (WorldEdit not found)"));
    }

    private void loadConfig() {
        allowDownloads = plugin.getConfig().getBoolean("schematics.allow-downloads", true);
        maxDownloadSize = plugin.getConfig().getInt("schematics.max-download-size", 10);
        pasteAir = plugin.getConfig().getBoolean("schematics.paste-air", false);
    }

    private void initializeFolder() {
        if (!schematicFolder.exists()) {
            schematicFolder.mkdirs();
        }
    }

    /**
     * Initialize WorldEdit integration
     */
    private void initializeWorldEdit() {
        worldEditPlugin = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin != null && worldEditPlugin.isEnabled()) {
            worldEditEnabled = true;
            plugin.getLogger().info("WorldEdit integration enabled for schematics");
        } else {
            worldEditEnabled = false;
            plugin.getLogger().warning("WorldEdit not found - schematic paste will use fallback method");
        }
    }

    // ==================== DATA STRUCTURES ====================

    public static class SchematicInfo {
        public String name;
        public String fileName;
        public File file;
        public SchematicFormat format;
        public long fileSize;

        public enum SchematicFormat {
            WORLDEDIT_SCHEM,    // .schem (Sponge format)
            WORLDEDIT_SCHEMATIC, // .schematic (MCEdit format)
            JSON                 // .json (Jarvis format)
        }
    }

    // ==================== FOLDER SCANNING ====================

    /**
     * Scan the schematics folder for all supported formats
     */
    public void scanFolder() {
        availableSchematics.clear();

        File[] files = schematicFolder.listFiles();
        if (files == null) return;

        for (File file : files) {
            String fileName = file.getName().toLowerCase();
            SchematicInfo info = new SchematicInfo();
            info.file = file;
            info.fileName = file.getName();
            info.fileSize = file.length();

            if (fileName.endsWith(".schem")) {
                info.format = SchematicInfo.SchematicFormat.WORLDEDIT_SCHEM;
                info.name = file.getName().replace(".schem", "");
            } else if (fileName.endsWith(".schematic")) {
                info.format = SchematicInfo.SchematicFormat.WORLDEDIT_SCHEMATIC;
                info.name = file.getName().replace(".schematic", "");
            } else if (fileName.endsWith(".json")) {
                info.format = SchematicInfo.SchematicFormat.JSON;
                info.name = file.getName().replace(".json", "");
            } else {
                continue; // Skip unsupported formats
            }

            availableSchematics.put(info.name.toLowerCase(), info);
        }

        plugin.getLogger().info("Found " + availableSchematics.size() + " schematics in folder");
    }

    // ==================== SCHEMATIC COMMANDS ====================

    /**
     * List available schematics
     */
    public void listSchematics(Player player) {
        scanFolder(); // Refresh list

        if (availableSchematics.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No schematics available.");
            player.sendMessage(ChatColor.GRAY + "Add .schem or .schematic files to:");
            player.sendMessage(ChatColor.WHITE + schematicFolder.getPath());
            return;
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "======== Available Schematics ========");

        for (SchematicInfo info : availableSchematics.values()) {
            String formatStr = switch (info.format) {
                case WORLDEDIT_SCHEM -> ChatColor.AQUA + "[SCHEM]";
                case WORLDEDIT_SCHEMATIC -> ChatColor.AQUA + "[SCHEMATIC]";
                case JSON -> ChatColor.YELLOW + "[JSON]";
            };

            String sizeStr = formatFileSize(info.fileSize);
            player.sendMessage(formatStr + " " + ChatColor.GOLD + info.name +
                ChatColor.GRAY + " (" + sizeStr + ")");
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "Use: /jarvis schematic paste <name>");
        if (worldEditEnabled) {
            player.sendMessage(ChatColor.GREEN + "WorldEdit: " + ChatColor.WHITE + "Enabled");
        } else {
            player.sendMessage(ChatColor.RED + "WorldEdit: " + ChatColor.WHITE + "Not found (limited features)");
        }
        player.sendMessage(ChatColor.GREEN + "====================================");
    }

    /**
     * Paste a schematic at player's location using WorldEdit
     */
    public void pasteSchematic(Player player, String name) {
        SchematicInfo info = availableSchematics.get(name.toLowerCase());

        if (info == null) {
            // Try refreshing and checking again
            scanFolder();
            info = availableSchematics.get(name.toLowerCase());
        }

        if (info == null) {
            player.sendMessage(ChatColor.RED + "Schematic not found: " + name);
            player.sendMessage(ChatColor.GRAY + "Use /jarvis schematic list to see available schematics");
            return;
        }

        // Check if NPC is summoned
        if (plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId()) == null) {
            player.sendMessage(ChatColor.RED + "Summon Jarvis first with /jarvis summon");
            return;
        }

        if (!worldEditEnabled) {
            player.sendMessage(ChatColor.RED + "WorldEdit is required for schematic pasting.");
            player.sendMessage(ChatColor.GRAY + "Please install WorldEdit on your server.");
            return;
        }

        // Use WorldEdit to paste
        player.sendMessage(ChatColor.YELLOW + "Jarvis: Pasting schematic: " + ChatColor.WHITE + info.name);

        final SchematicInfo finalInfo = info;
        pasteWithWorldEdit(player, finalInfo);
    }

    /**
     * Paste schematic using WorldEdit API
     */
    private void pasteWithWorldEdit(Player player, SchematicInfo info) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // Get WorldEdit classes via reflection to avoid hard dependency
                    Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
                    Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                    Class<?> clipboardFormatClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
                    Class<?> clipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
                    Class<?> editSessionFactoryClass = Class.forName("com.sk89q.worldedit.EditSessionFactory");
                    Class<?> editSessionClass = Class.forName("com.sk89q.worldedit.EditSession");

                    // Get WorldEdit instance
                    Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);

                    // Adapt player to WorldEdit
                    Object wePlayer = bukkitAdapterClass.getMethod("adapt", Player.class).invoke(null, player);

                    // Adapt world
                    Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
                        .invoke(null, player.getWorld());

                    // Find clipboard format for the file
                    Object format = clipboardFormatClass.getMethod("findByFile", File.class)
                        .invoke(null, info.file);

                    if (format == null) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.RED + "Unknown schematic format: " + info.fileName);
                            }
                        }.runTask(plugin);
                        return;
                    }

                    // Load the schematic
                    Object clipboard;
                    try (FileInputStream fis = new FileInputStream(info.file)) {
                        Class<?> clipboardReaderClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardReader");
                        Object reader = format.getClass().getMethod("getReader", InputStream.class).invoke(format, fis);
                        clipboard = clipboardReaderClass.getMethod("read").invoke(reader);
                    }

                    // Get player location as BlockVector3
                    Location loc = player.getLocation();
                    Class<?> blockVector3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                    Object pasteLocation = blockVector3Class.getMethod("at", int.class, int.class, int.class)
                        .invoke(null, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

                    // Create EditSession
                    Object editSessionFactory = worldEditClass.getMethod("getEditSessionFactory").invoke(worldEdit);
                    Object editSession = editSessionFactoryClass.getMethod("getEditSession",
                        Class.forName("com.sk89q.worldedit.world.World"), int.class)
                        .invoke(editSessionFactory, weWorld, -1);

                    // Create clipboard holder and paste operation
                    Class<?> clipboardHolderClass = Class.forName("com.sk89q.worldedit.session.ClipboardHolder");
                    Object clipboardHolder = clipboardHolderClass.getConstructor(clipboardClass)
                        .newInstance(clipboard);

                    // Build paste operation
                    Object pasteBuilder = clipboardHolderClass.getMethod("createPaste", editSessionClass)
                        .invoke(clipboardHolder, editSession);

                    // Set paste location
                    pasteBuilder.getClass().getMethod("to", blockVector3Class).invoke(pasteBuilder, pasteLocation);

                    // Set ignore air blocks option
                    pasteBuilder.getClass().getMethod("ignoreAirBlocks", boolean.class)
                        .invoke(pasteBuilder, !pasteAir);

                    // Build and complete the operation
                    Class<?> operationClass = Class.forName("com.sk89q.worldedit.function.operation.Operation");
                    Object operation = pasteBuilder.getClass().getMethod("build").invoke(pasteBuilder);

                    Class<?> operationsClass = Class.forName("com.sk89q.worldedit.function.operation.Operations");
                    operationsClass.getMethod("complete", operationClass).invoke(null, operation);

                    // Close edit session
                    editSessionClass.getMethod("close").invoke(editSession);

                    // Get block count from clipboard
                    Object region = clipboardClass.getMethod("getRegion").invoke(clipboard);
                    Object volume = region.getClass().getMethod("getVolume").invoke(region);
                    int blockCount = ((Number) volume).intValue();

                    // Notify player on main thread
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage("");
                            player.sendMessage(ChatColor.GREEN + "========================================");
                            player.sendMessage(ChatColor.GOLD + "  Schematic Pasted: " + ChatColor.YELLOW + info.name);
                            player.sendMessage(ChatColor.GREEN + "========================================");
                            player.sendMessage(ChatColor.WHITE + "  Blocks: ~" + blockCount);
                            player.sendMessage(ChatColor.WHITE + "  Location: " + loc.getBlockX() + ", " +
                                loc.getBlockY() + ", " + loc.getBlockZ());
                            player.sendMessage(ChatColor.GRAY + "  Use //undo to revert if needed");
                            player.sendMessage(ChatColor.GREEN + "========================================");
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("WorldEdit paste failed: " + e.getMessage());
                    e.printStackTrace();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Failed to paste schematic: " + e.getMessage());
                            player.sendMessage(ChatColor.GRAY + "Check console for details.");
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Save player's WorldEdit selection as a schematic
     */
    public void saveSchematic(Player player, String name) {
        if (!worldEditEnabled) {
            player.sendMessage(ChatColor.RED + "WorldEdit is required for saving schematics.");
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // Get WorldEdit classes
                    Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
                    Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                    Class<?> localSessionClass = Class.forName("com.sk89q.worldedit.LocalSession");
                    Class<?> clipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");

                    // Get WorldEdit instance
                    Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);

                    // Get session manager
                    Object sessionManager = worldEditClass.getMethod("getSessionManager").invoke(worldEdit);

                    // Adapt player
                    Object wePlayer = bukkitAdapterClass.getMethod("adapt", Player.class).invoke(null, player);

                    // Get player's session
                    Object session = sessionManager.getClass().getMethod("get",
                        Class.forName("com.sk89q.worldedit.extension.platform.Actor"))
                        .invoke(sessionManager, wePlayer);

                    // Get clipboard from session
                    Class<?> clipboardHolderClass = Class.forName("com.sk89q.worldedit.session.ClipboardHolder");
                    Object clipboardHolder;
                    try {
                        clipboardHolder = localSessionClass.getMethod("getClipboard").invoke(session);
                    } catch (Exception e) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.RED + "No clipboard found!");
                                player.sendMessage(ChatColor.GRAY + "Use //copy to copy a selection first.");
                            }
                        }.runTask(plugin);
                        return;
                    }

                    Object clipboard = clipboardHolderClass.getMethod("getClipboard").invoke(clipboardHolder);

                    // Create output file
                    String fileName = name.endsWith(".schem") ? name : name + ".schem";
                    File outputFile = new File(schematicFolder, fileName);

                    // Get Sponge schematic format
                    Class<?> clipboardFormatsClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat");
                    Object spongeFormat = Enum.valueOf((Class<Enum>) clipboardFormatsClass, "SPONGE_SCHEMATIC");

                    // Write schematic
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        Object writer = spongeFormat.getClass().getMethod("getWriter", OutputStream.class)
                            .invoke(spongeFormat, fos);
                        writer.getClass().getMethod("write", clipboardClass).invoke(writer, clipboard);
                        writer.getClass().getMethod("close").invoke(writer);
                    }

                    // Refresh schematics list
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            scanFolder();
                            player.sendMessage(ChatColor.GREEN + "Schematic saved: " + ChatColor.YELLOW + fileName);
                            player.sendMessage(ChatColor.GRAY + "Location: " + outputFile.getPath());
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save schematic: " + e.getMessage());
                    e.printStackTrace();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Failed to save schematic: " + e.getMessage());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Rotate clipboard before pasting
     */
    public void rotateAndPaste(Player player, String name, int degrees) {
        if (!worldEditEnabled) {
            player.sendMessage(ChatColor.RED + "WorldEdit is required for rotation.");
            return;
        }

        SchematicInfo info = availableSchematics.get(name.toLowerCase());
        if (info == null) {
            player.sendMessage(ChatColor.RED + "Schematic not found: " + name);
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Jarvis: Pasting " + info.name + " rotated " + degrees + " degrees...");

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // Similar to pasteWithWorldEdit but with rotation transform
                    Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
                    Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                    Class<?> clipboardFormatClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
                    Class<?> clipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
                    Class<?> affineTransformClass = Class.forName("com.sk89q.worldedit.math.transform.AffineTransform");

                    Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
                    Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
                        .invoke(null, player.getWorld());

                    Object format = clipboardFormatClass.getMethod("findByFile", File.class)
                        .invoke(null, info.file);

                    Object clipboard;
                    try (FileInputStream fis = new FileInputStream(info.file)) {
                        Object reader = format.getClass().getMethod("getReader", InputStream.class).invoke(format, fis);
                        clipboard = reader.getClass().getMethod("read").invoke(reader);
                    }

                    Location loc = player.getLocation();
                    Class<?> blockVector3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                    Object pasteLocation = blockVector3Class.getMethod("at", int.class, int.class, int.class)
                        .invoke(null, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

                    Object editSessionFactory = worldEditClass.getMethod("getEditSessionFactory").invoke(worldEdit);
                    Object editSession = editSessionFactory.getClass().getMethod("getEditSession",
                        Class.forName("com.sk89q.worldedit.world.World"), int.class)
                        .invoke(editSessionFactory, weWorld, -1);

                    // Create transform with rotation
                    Object transform = affineTransformClass.getConstructor().newInstance();
                    transform = affineTransformClass.getMethod("rotateY", double.class)
                        .invoke(transform, (double) degrees);

                    Class<?> clipboardHolderClass = Class.forName("com.sk89q.worldedit.session.ClipboardHolder");
                    Object clipboardHolder = clipboardHolderClass.getConstructor(clipboardClass)
                        .newInstance(clipboard);

                    // Set transform
                    clipboardHolderClass.getMethod("setTransform",
                        Class.forName("com.sk89q.worldedit.math.transform.Transform"))
                        .invoke(clipboardHolder, transform);

                    Object pasteBuilder = clipboardHolderClass.getMethod("createPaste",
                        Class.forName("com.sk89q.worldedit.EditSession"))
                        .invoke(clipboardHolder, editSession);

                    pasteBuilder.getClass().getMethod("to", blockVector3Class).invoke(pasteBuilder, pasteLocation);
                    pasteBuilder.getClass().getMethod("ignoreAirBlocks", boolean.class).invoke(pasteBuilder, !pasteAir);

                    Object operation = pasteBuilder.getClass().getMethod("build").invoke(pasteBuilder);

                    Class<?> operationsClass = Class.forName("com.sk89q.worldedit.function.operation.Operations");
                    operationsClass.getMethod("complete",
                        Class.forName("com.sk89q.worldedit.function.operation.Operation"))
                        .invoke(null, operation);

                    editSession.getClass().getMethod("close").invoke(editSession);

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.GREEN + "Pasted " + info.name + " rotated " + degrees + " degrees!");
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("Rotated paste failed: " + e.getMessage());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.sendMessage(ChatColor.RED + "Failed to paste: " + e.getMessage());
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // ==================== UTILITY ====================

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ==================== GETTERS ====================

    public File getSchematicFolder() {
        return schematicFolder;
    }

    public Collection<SchematicInfo> getSchematics() {
        return availableSchematics.values();
    }

    public SchematicInfo getSchematic(String name) {
        return availableSchematics.get(name.toLowerCase());
    }

    public int getSchematicCount() {
        return availableSchematics.size();
    }

    public boolean isWorldEditEnabled() {
        return worldEditEnabled;
    }
}
