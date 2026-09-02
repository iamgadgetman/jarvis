package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DepositManager - Jarvis's pack-mule service (v0.2.0).
 *
 * Players register a deposit chest ("/jarvis chest" while looking at one);
 * Jarvis can then carry his loot to it on command ("/jarvis deposit") or
 * automatically when his bags fill up during mining. Chest locations are
 * persisted to data.yml so they survive restarts.
 */
public class DepositManager {

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Map<UUID, Location> chests = new ConcurrentHashMap<>();
    private final Map<UUID, Location> homes = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.List<Location>> patrols = new ConcurrentHashMap<>();
    private final File dataFile;

    private static final double CHEST_REACH = 2.8;
    private static final int WALK_NUDGE_TICKS = 10;   // 10s of stalled walking before nudging
    private static final double MAX_DEPOSIT_DISTANCE = 64.0;

    public DepositManager(Jarvis plugin, JarvisNPC host) {
        this.plugin = plugin;
        this.host = host;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    // ==================== CHEST REGISTRY ====================

    /** Register the container the player is looking at as their deposit chest. */
    public void setChest(Player player) {
        Block target = player.getTargetBlockExact(6);
        if (target == null || !(target.getState() instanceof Container)) {
            host.say(player, "Do look at a chest or barrel when you say that, sir.");
            return;
        }
        chests.put(player.getUniqueId(), target.getLocation());
        save();
        host.say(player, "Noted, sir. That " + target.getType().name().toLowerCase().replace('_', ' ')
                + " is now where I'll deposit your spoils.");
    }

    public Location getChest(Player player) {
        Location loc = chests.get(player.getUniqueId());
        if (loc == null || loc.getWorld() == null) return null;
        if (!(loc.getBlock().getState() instanceof Container)) return null; // chest was broken
        return loc;
    }

    public boolean hasChest(Player player) {
        return getChest(player) != null;
    }

    // ==================== HOME REGISTRY (v0.6.0) ====================

    public void setHome(Player player, Location loc) {
        homes.put(player.getUniqueId(), loc.clone());
        save();
    }

    public Location getHome(Player player) {
        Location loc = homes.get(player.getUniqueId());
        return (loc == null || loc.getWorld() == null) ? null : loc.clone();
    }

    // ==================== PATROL ROUTES (v0.7.0) ====================

    public int addPatrolPoint(Player player, Location loc) {
        java.util.List<Location> route = patrols.computeIfAbsent(
                player.getUniqueId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        route.add(loc.getBlock().getLocation().add(0.5, 0, 0.5));
        save();
        return route.size();
    }

    public void clearPatrol(Player player) {
        patrols.remove(player.getUniqueId());
        save();
    }

    public java.util.List<Location> getPatrol(Player player) {
        java.util.List<Location> route = patrols.get(player.getUniqueId());
        if (route == null) return java.util.List.of();
        java.util.List<Location> valid = new java.util.ArrayList<>();
        for (Location l : route) {
            if (l.getWorld() != null) valid.add(l.clone());
        }
        return valid;
    }

    // ==================== DEPOSITING ====================

    /**
     * Walk to the deposit chest (or the nearest container if none is
     * registered) and empty the loot slots into it. Standalone task —
     * cancels whatever else Jarvis was doing.
     */
    public void deposit(Player player) {
        if (!host.getProvider().isSpawned(player)) {
            host.say(player, "Summon me first, sir — /jarvis summon.");
            return;
        }

        Location chest = getChest(player);
        if (chest == null) {
            chest = findNearbyContainer(host.getCurrentLocation(player), 8);
        }
        if (chest == null) {
            host.say(player, "I have no chest on record, sir. Look at one and say '/jarvis chest'.");
            return;
        }
        if (host.lootSlotsUsed(player) == 0) {
            host.say(player, "My bags are already empty, sir.");
            return;
        }

        host.stopTask(player);
        host.say(player, "Delivering the goods, sir.");
        startDepositRun(player, chest, () -> {});
    }

    /**
     * The walking + dumping routine. Calls onComplete afterwards (used by
     * the branch miner to resume digging after an auto-deposit).
     */
    void startDepositRun(Player player, Location chest, Runnable onComplete) {
        INPCProvider provider = host.getProvider();
        final Location chestLoc = chest;
        host.applyNavigatorDefaults(player, null);
        host.navigateTo(player, chestLoc.clone().add(0.5, 1, 0.5), null);

        BukkitRunnable task = new BukkitRunnable() {
            int stalled = 0;
            Location lastPos = null;

            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }

                Location npcLoc = host.getCurrentLocation(player);
                double dist = npcLoc.distance(chestLoc.clone().add(0.5, 0.5, 0.5));

                if (dist <= CHEST_REACH) {
                    cancel();
                    host.taskDone(player, this);
                    dumpInto(player, chestLoc);
                    onComplete.run();
                    return;
                }

                if (dist > MAX_DEPOSIT_DISTANCE) {
                    cancel();
                    host.taskDone(player, this);
                    host.say(player, "The chest is rather far from here, sir. I'll hold onto things for now.");
                    onComplete.run();
                    return;
                }

                // Progress watchdog
                if (lastPos != null && npcLoc.distance(lastPos) < 0.2) {
                    stalled++;
                } else {
                    stalled = 0;
                }
                lastPos = npcLoc.clone();

                if (!provider.isNavigating(player)) {
                    host.navigateTo(player, chestLoc.clone().add(0.5, 1, 0.5), null);
                }

                if (stalled > WALK_NUDGE_TICKS) {
                    // Last resort within butler rules: short-range teleport
                    provider.cancelNavigation(player);
                    Location safe = chestLoc.clone().add(0.5, 1, 0.5);
                    safe.setYaw(npcLoc.getYaw());
                    provider.teleport(player, safe);
                    stalled = 0;
                }
            }
        };

        task.runTaskTimer(plugin, 20L, 20L);
        host.registerTask(player, task);
    }

    /** Move everything in the loot slots (1..35) into the container. */
    private void dumpInto(Player player, Location chestLoc) {
        if (!(chestLoc.getBlock().getState() instanceof Container container)) {
            host.say(player, "The chest appears to have vanished, sir.");
            return;
        }

        INPCProvider provider = host.getProvider();
        ItemStack[] contents = provider.getInventoryContents(player);
        int moved = 0, leftBehind = 0;

        // v0.8.0: slots 1+ all go in the chest — even diamond tools; only
        // slot 0 (his hand) is the kit.
        for (int i = 1; i < Math.min(36, contents.length); i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR) continue;

            Map<Integer, ItemStack> overflow = container.getInventory().addItem(item.clone());
            if (overflow.isEmpty()) {
                moved += item.getAmount();
                contents[i] = null;
            } else {
                ItemStack rest = overflow.values().iterator().next();
                moved += item.getAmount() - rest.getAmount();
                contents[i] = rest;
                leftBehind += rest.getAmount();
            }
        }

        provider.setInventoryContents(player, contents);

        World world = chestLoc.getWorld();
        if (world != null) {
            world.playSound(chestLoc, Sound.BLOCK_CHEST_OPEN, 0.7f, 1.0f);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> world.playSound(chestLoc, Sound.BLOCK_CHEST_CLOSE, 0.7f, 1.0f), 15L);
        }

        if (leftBehind > 0) {
            host.say(player, "Deposited " + moved + " items, sir — the chest is full; "
                    + leftBehind + " remain with me.");
        } else {
            host.say(player, "Deposited " + moved + " items, sir. All squared away.");
        }
    }

    /** Find the nearest chest/barrel within radius of a location. */
    private Location findNearbyContainer(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return null;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        Location best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = world.getBlockAt(cx + x, cy + y, cz + z);
                    Material t = b.getType();
                    if (t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL) {
                        double d = x * x + y * y + z * z;
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = b.getLocation();
                        }
                    }
                }
            }
        }
        return best;
    }

    // ==================== PERSISTENCE ====================

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        loadSection(yaml, "deposit-chests", chests, false);
        loadSection(yaml, "homes", homes, true);

        var patrolSection = yaml.getConfigurationSection("patrols");
        if (patrolSection != null) {
            for (String key : patrolSection.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    java.util.List<Location> route = new java.util.concurrent.CopyOnWriteArrayList<>();
                    var pts = patrolSection.getConfigurationSection(key);
                    if (pts == null) continue;
                    for (String idx : pts.getKeys(false)) {
                        String worldName = pts.getString(idx + ".world");
                        World world = worldName != null ? plugin.getServer().getWorld(worldName) : null;
                        if (world == null) continue;
                        route.add(new Location(world,
                                pts.getDouble(idx + ".x"),
                                pts.getDouble(idx + ".y"),
                                pts.getDouble(idx + ".z")));
                    }
                    if (!route.isEmpty()) patrols.put(id, route);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void loadSection(YamlConfiguration yaml, String name, Map<UUID, Location> into, boolean precise) {
        var section = yaml.getConfigurationSection(name);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String worldName = section.getString(key + ".world");
                World world = worldName != null ? plugin.getServer().getWorld(worldName) : null;
                if (world == null) continue;
                Location loc = precise
                        ? new Location(world,
                            section.getDouble(key + ".x"),
                            section.getDouble(key + ".y"),
                            section.getDouble(key + ".z"))
                        : new Location(world,
                            section.getInt(key + ".x"),
                            section.getInt(key + ".y"),
                            section.getInt(key + ".z"));
                into.put(id, loc);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Location> e : new HashMap<>(chests).entrySet()) {
            String base = "deposit-chests." + e.getKey();
            Location l = e.getValue();
            if (l.getWorld() == null) continue;
            yaml.set(base + ".world", l.getWorld().getName());
            yaml.set(base + ".x", l.getBlockX());
            yaml.set(base + ".y", l.getBlockY());
            yaml.set(base + ".z", l.getBlockZ());
        }
        for (Map.Entry<UUID, Location> e : new HashMap<>(homes).entrySet()) {
            String base = "homes." + e.getKey();
            Location l = e.getValue();
            if (l.getWorld() == null) continue;
            yaml.set(base + ".world", l.getWorld().getName());
            yaml.set(base + ".x", l.getX());
            yaml.set(base + ".y", l.getY());
            yaml.set(base + ".z", l.getZ());
        }
        for (Map.Entry<UUID, java.util.List<Location>> e : new HashMap<>(patrols).entrySet()) {
            int i = 0;
            for (Location l : e.getValue()) {
                if (l.getWorld() == null) continue;
                String base = "patrols." + e.getKey() + "." + i++;
                yaml.set(base + ".world", l.getWorld().getName());
                yaml.set(base + ".x", l.getX());
                yaml.set(base + ".y", l.getY());
                yaml.set(base + ".z", l.getZ());
            }
        }
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
