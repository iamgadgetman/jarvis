package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.schematics.SchematicLibrary.SchematicInfo;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;

/**
 * The Paper face of the schematic library.
 *
 * <p>Scanning, matching, the native .schem paste and the litematic
 * conversion live in core's {@link SchematicLibrary}. What stays here is
 * WorldEdit: the JSON paste it accelerates, saving a clipboard, and rotated
 * pastes, all reached by reflection so WorldEdit stays an optional plugin.
 */
public class SchematicManager {

    private final Jarvis plugin;
    private final SchematicLibrary library;

    // WorldEdit integration
    private boolean worldEditEnabled = false;
    private boolean pasteAir = false;

    public SchematicManager(Jarvis plugin, ButlerService butlers) {
        this.plugin = plugin;
        this.pasteAir = plugin.getConfig().getBoolean("schematics.paste-air", false);
        this.library = new SchematicLibrary(plugin.getPlatform(), butlers);
        initializeWorldEdit();
    }

    /** The platform-free library behind this facade. */
    public SchematicLibrary library() {
        return library;
    }

    private Owner o(Player player) {
        return plugin.owner(player);
    }

    /** Initialize WorldEdit integration. */
    private void initializeWorldEdit() {
        Plugin worldEditPlugin = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin != null && worldEditPlugin.isEnabled()) {
            worldEditEnabled = true;
            library.setAccelerator(new SchematicLibrary.Accelerator() {
                @Override public String name() { return "WorldEdit"; }
                @Override public void pasteJson(Owner player, SchematicInfo info) {
                    plugin.getPlatform().player(player).ifPresent(p -> pasteWithWorldEdit(p, info));
                }
            });
            plugin.getLogger().info("WorldEdit integration enabled for schematics");
        } else {
            worldEditEnabled = false;
            plugin.getLogger().warning("WorldEdit not found - schematic paste will use fallback method");
        }
    }

    // ==================== DELEGATED TO CORE ====================

    public void scanFolder() { library.scanFolder(); }
    public void listSchematics(Player player) { library.listSchematics(o(player)); }
    public void pasteSchematic(Player player, String name) { library.pasteSchematic(o(player), name); }
    public String bestMatchName(String query) { return library.bestMatchName(query); }
    public int bestMatchScore(String query) { return library.bestMatchScore(query); }
    public String bestMatchName(String query, RequestFeatures features) { return library.bestMatchName(query, features); }
    public int bestMatchScore(String query, RequestFeatures features) { return library.bestMatchScore(query, features); }
    public Path getSchematicFolder() { return library.getSchematicFolder(); }
    public Collection<SchematicInfo> getSchematics() { return library.getSchematics(); }
    public SchematicInfo getSchematic(String name) { return library.getSchematic(name); }
    public int getSchematicCount() { return library.getSchematicCount(); }
    public void convertLitematic(Player player, String name) { library.convertLitematic(o(player), name); }
    public void convertAllLitematics(Player player) { library.convertAllLitematics(o(player)); }
    public void showLitematicFiles(Player player) { library.showLitematicFiles(o(player)); }

    public boolean isWorldEditEnabled() {
        return worldEditEnabled;
    }

    // ==================== WORLDEDIT ====================

    /** Paste schematic using WorldEdit API. */
    private void pasteWithWorldEdit(Player player, SchematicInfo info) {
        File file = info.file().toFile();
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

                    // Adapt world
                    Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
                        .invoke(null, player.getWorld());

                    // Find clipboard format for the file
                    Object format = clipboardFormatClass.getMethod("findByFile", File.class)
                        .invoke(null, file);

                    if (format == null) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                player.sendMessage(ChatColor.RED + "Unknown schematic format: " + info.fileName());
                            }
                        }.runTask(plugin);
                        return;
                    }

                    // Load the schematic
                    Object clipboard;
                    try (FileInputStream fis = new FileInputStream(file)) {
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
                            player.sendMessage(ChatColor.GOLD + "  Schematic Pasted: " + ChatColor.YELLOW + info.name());
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

    /** Save player's WorldEdit selection as a schematic. */
    @SuppressWarnings({"unchecked", "rawtypes"})
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
                    File outputFile = library.getSchematicFolder().resolve(fileName).toFile();

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
                            library.scanFolder();
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
     * Rotate clipboard before pasting.
     * Note: Rotation requires WorldEdit.
     */
    public void rotateAndPaste(Player player, String name, int degrees) {
        if (!worldEditEnabled) {
            player.sendMessage(ChatColor.RED + "Rotation requires WorldEdit.");
            player.sendMessage(ChatColor.GRAY + "Use /jarvis schematic paste " + name + " for normal paste.");
            return;
        }

        SchematicInfo info = library.findSchematic(name);
        if (info == null) {
            player.sendMessage(ChatColor.RED + "Schematic not found: " + name);
            player.sendMessage(ChatColor.GRAY + "Use /jarvis schematic list to see available schematics");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Jarvis: Pasting " + info.name() + " rotated " + degrees + " degrees...");
        File file = info.file().toFile();

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
                        .invoke(null, file);

                    Object clipboard;
                    try (FileInputStream fis = new FileInputStream(file)) {
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
                            player.sendMessage(ChatColor.GREEN + "Pasted " + info.name() + " rotated " + degrees + " degrees!");
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
}
