package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.building.UndoService;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.world.BlockPos;
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

/**
 * WorldEdit's contribution to the schematic library.
 *
 * <p>Scanning, matching, the native .schem paste and the litematic
 * conversion live in core's {@link SchematicLibrary}. What is here is
 * WorldEdit: the JSON paste it accelerates, saving a clipboard, and rotated
 * pastes, all reached by reflection so WorldEdit stays an optional plugin.
 */
public class SchematicManager implements SchematicExtras {

    private final Jarvis plugin;
    private final SchematicLibrary library;
    private final UndoService undo;

    // WorldEdit integration
    private boolean worldEditEnabled = false;
    private boolean pasteAir = false;

    public SchematicManager(Jarvis plugin, SchematicLibrary library, UndoService undo) {
        this.plugin = plugin;
        this.library = library;
        this.undo = undo;
        this.pasteAir = plugin.getConfig().getBoolean("schematics.paste-air", false);
        initializeWorldEdit();
    }

    /** Initialize WorldEdit integration. */
    private void initializeWorldEdit() {
        Plugin worldEditPlugin = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin != null && worldEditPlugin.isEnabled()) {
            worldEditEnabled = true;
            library.setAccelerator(new SchematicLibrary.Accelerator() {
                @Override public String name() { return "WorldEdit"; }
                @Override public void pasteJson(Owner player, SchematicInfo info) {
                    plugin.getPlatform().player(player).ifPresent(p -> pasteWithWorldEdit(player, p, info));
                }
            });
            plugin.getLogger().info("WorldEdit integration enabled for schematics");
        } else {
            worldEditEnabled = false;
            plugin.getLogger().warning("WorldEdit not found - schematic paste will use fallback method");
        }
    }

    public boolean isWorldEditEnabled() {
        return worldEditEnabled;
    }

    // ==================== WORLDEDIT ====================

    /** Paste schematic using WorldEdit API. */
    private void pasteWithWorldEdit(Owner owner, Player player, SchematicInfo info) {
        loadThenPaste(owner, player, info, 0, blockCount -> {
            player.sendMessage("");
            player.sendMessage(ChatColor.GREEN + "========================================");
            player.sendMessage(ChatColor.GOLD + "  Schematic Pasted: " + ChatColor.YELLOW + info.name());
            player.sendMessage(ChatColor.GREEN + "========================================");
            if (blockCount >= 0) {
                player.sendMessage(ChatColor.WHITE + "  Blocks changed: " + blockCount);
            }
            Location loc = player.getLocation();
            player.sendMessage(ChatColor.WHITE + "  Location: " + loc.getBlockX() + ", " +
                loc.getBlockY() + ", " + loc.getBlockZ());
            if (undo.isEnabled() && blockCount >= 0) {
                player.sendMessage(ChatColor.GRAY + "  Use /jarvis undo to revert");
            }
            player.sendMessage(ChatColor.GREEN + "========================================");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        });
    }

    /**
     * Read the file off the server thread, then paste on it. The paste
     * itself runs on the server thread, as WorldEdit's own //paste does, so
     * the region can be read before and after it and the difference
     * recorded for {@code /jarvis undo}.
     *
     * @param degrees rotation about Y, 0 for none
     * @param done    told how many blocks changed, on the server thread
     */
    private void loadThenPaste(Owner owner, Player player, SchematicInfo info, int degrees,
                               java.util.function.IntConsumer done) {
        File file = info.file().toFile();
        new BukkitRunnable() {
            @Override
            public void run() {
                Object clipboard;
                try {
                    Class<?> clipboardFormatClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
                    Object format = clipboardFormatClass.getMethod("findByFile", File.class).invoke(null, file);
                    if (format == null) {
                        tell(player, ChatColor.RED + "Unknown schematic format: " + info.fileName());
                        return;
                    }
                    try (FileInputStream fis = new FileInputStream(file)) {
                        Class<?> clipboardReaderClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardReader");
                        Object reader = format.getClass().getMethod("getReader", InputStream.class).invoke(format, fis);
                        clipboard = clipboardReaderClass.getMethod("read").invoke(reader);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("WorldEdit could not read " + info.fileName() + ": " + e.getMessage());
                    tell(player, ChatColor.RED + "Failed to read schematic: " + e.getMessage());
                    return;
                }
                final Object loaded = clipboard;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) return;
                        try {
                            int changed = pasteClipboard(owner, player, info.name(), loaded, degrees);
                            done.accept(changed);
                        } catch (Exception e) {
                            plugin.getLogger().warning("WorldEdit paste failed: " + e.getMessage());
                            e.printStackTrace();
                            player.sendMessage(ChatColor.RED + "Failed to paste schematic: " + e.getMessage());
                            player.sendMessage(ChatColor.GRAY + "Check console for details.");
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Paste a loaded clipboard where the player stands. Server thread.
     * Records what it replaced into the undo history.
     *
     * @return how many blocks changed
     */
    private int pasteClipboard(Owner owner, Player player, String name, Object clipboard, int degrees) throws Exception {
        Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
        Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
        Class<?> clipboardClass = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
        Class<?> editSessionClass = Class.forName("com.sk89q.worldedit.EditSession");
        Class<?> blockVector3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3");

        Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
        Object weWorld = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class)
            .invoke(null, player.getWorld());

        Location loc = player.getLocation();
        int tx = loc.getBlockX(), ty = loc.getBlockY(), tz = loc.getBlockZ();
        Object pasteLocation = blockVector3Class.getMethod("at", int.class, int.class, int.class)
            .invoke(null, tx, ty, tz);

        // Where the paste will land: the clipboard's region, moved so its
        // origin sits at the player, and for a rotated paste turned about
        // that point. Either turning direction is covered, so the box holds
        // whichever way WorldEdit rotates.
        Object region = clipboardClass.getMethod("getRegion").invoke(clipboard);
        Object origin = clipboardClass.getMethod("getOrigin").invoke(clipboard);
        Object minPt = region.getClass().getMethod("getMinimumPoint").invoke(region);
        Object maxPt = region.getClass().getMethod("getMaximumPoint").invoke(region);
        int ox = coord(origin, "x"), oy = coord(origin, "y"), oz = coord(origin, "z");
        int[] lo = {coord(minPt, "x") - ox, coord(minPt, "y") - oy, coord(minPt, "z") - oz};
        int[] hi = {coord(maxPt, "x") - ox, coord(maxPt, "y") - oy, coord(maxPt, "z") - oz};
        int minX = lo[0], maxX = hi[0], minZ = lo[2], maxZ = hi[2];
        if (degrees % 360 != 0) {
            minX = Integer.MAX_VALUE; maxX = Integer.MIN_VALUE; minZ = Integer.MAX_VALUE; maxZ = Integer.MIN_VALUE;
            for (int sign : new int[]{1, -1}) {
                double rad = Math.toRadians(sign * degrees);
                double cos = Math.cos(rad), sin = Math.sin(rad);
                for (int cx : new int[]{lo[0], hi[0]}) {
                    for (int cz : new int[]{lo[2], hi[2]}) {
                        int rx = (int) Math.round(cx * cos - cz * sin);
                        int rz = (int) Math.round(cx * sin + cz * cos);
                        minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
                        minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
                    }
                }
            }
        }

        com.gadgetman.jarvis.core.platform.World world = plugin.getPlatform().world(player.getWorld());
        UndoService.Recorder record = undo.begin(owner, world, "schematic: " + name
                + (degrees % 360 != 0 ? " (rotated " + degrees + ")" : ""), false);
        UndoService.Snapshot before = undo.isEnabled() ? undo.snapshot(world,
                new BlockPos(tx + minX, ty + lo[1], tz + minZ),
                new BlockPos(tx + maxX, ty + hi[1], tz + maxZ)) : null;
        if (undo.isEnabled() && before == null) {
            player.sendMessage(ChatColor.GRAY + "That one is too large to record for /jarvis undo.");
        }

        Object editSessionFactory = worldEditClass.getMethod("getEditSessionFactory").invoke(worldEdit);
        Object editSession = editSessionFactory.getClass().getMethod("getEditSession",
            Class.forName("com.sk89q.worldedit.world.World"), int.class)
            .invoke(editSessionFactory, weWorld, -1);

        Class<?> clipboardHolderClass = Class.forName("com.sk89q.worldedit.session.ClipboardHolder");
        Object clipboardHolder = clipboardHolderClass.getConstructor(clipboardClass).newInstance(clipboard);

        if (degrees % 360 != 0) {
            Class<?> affineTransformClass = Class.forName("com.sk89q.worldedit.math.transform.AffineTransform");
            Object transform = affineTransformClass.getConstructor().newInstance();
            transform = affineTransformClass.getMethod("rotateY", double.class).invoke(transform, (double) degrees);
            clipboardHolderClass.getMethod("setTransform",
                Class.forName("com.sk89q.worldedit.math.transform.Transform"))
                .invoke(clipboardHolder, transform);
        }

        Object pasteBuilder = clipboardHolderClass.getMethod("createPaste", editSessionClass)
            .invoke(clipboardHolder, editSession);
        pasteBuilder.getClass().getMethod("to", blockVector3Class).invoke(pasteBuilder, pasteLocation);
        pasteBuilder.getClass().getMethod("ignoreAirBlocks", boolean.class).invoke(pasteBuilder, !pasteAir);

        Class<?> operationClass = Class.forName("com.sk89q.worldedit.function.operation.Operation");
        Object operation = pasteBuilder.getClass().getMethod("build").invoke(pasteBuilder);
        Class<?> operationsClass = Class.forName("com.sk89q.worldedit.function.operation.Operations");
        try {
            operationsClass.getMethod("complete", operationClass).invoke(null, operation);
        } finally {
            // Closing flushes the session's queued changes to the world.
            editSessionClass.getMethod("close").invoke(editSession);
        }

        if (before == null) return -1;
        int changed = before.diffInto(record);
        undo.commit(record);
        return changed;
    }

    /** A BlockVector3 coordinate: x() in current WorldEdit, getX() in older releases. */
    private static int coord(Object vector, String axis) throws Exception {
        try {
            return ((Number) vector.getClass().getMethod(axis).invoke(vector)).intValue();
        } catch (NoSuchMethodException e) {
            String getter = "get" + Character.toUpperCase(axis.charAt(0)) + axis.substring(1);
            return ((Number) vector.getClass().getMethod(getter).invoke(vector)).intValue();
        }
    }

    private void tell(Player player, String message) {
        new BukkitRunnable() {
            @Override
            public void run() {
                player.sendMessage(message);
            }
        }.runTask(plugin);
    }

    /** Save player's WorldEdit selection as a schematic. */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void saveClipboard(Owner owner, String name) {
        Player player = plugin.getPlatform().player(owner).orElse(null);
        if (player == null) return;
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
    @Override
    public void rotateAndPaste(Owner owner, String name, int degrees) {
        Player player = plugin.getPlatform().player(owner).orElse(null);
        if (player == null) return;
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
        loadThenPaste(owner, player, info, degrees, changed -> {
            player.sendMessage(ChatColor.GREEN + "Pasted " + info.name() + " rotated " + degrees + " degrees!");
            if (undo.isEnabled() && changed >= 0) {
                player.sendMessage(ChatColor.GRAY + "Use /jarvis undo to revert.");
            }
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        });
    }
}
