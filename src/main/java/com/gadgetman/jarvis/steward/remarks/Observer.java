package com.gadgetman.jarvis.steward.remarks;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an {@link Observation} off a player.
 *
 * <p>Everything here is a getter that was going to be answered anyway — no
 * events are subscribed to, nothing is written down, and the cost of a reading
 * is one pass over 36 inventory slots and one nearby-entity query. Main thread
 * only, like anything else that touches the world.
 */
public final class Observer {

    private Observer() { }

    private static final int MONSTER_RADIUS = 16;

    /** Read the player. Never throws; returns {@code null} if the world will not answer. */
    public static Observation observe(Player player) {
        if (player == null || !player.isOnline()) return null;
        try {
            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world == null) return null;

            // ---- Inventory: what he is holding, what he has most of, what is left ----
            ItemStack held = player.getInventory().getItemInMainHand();
            String heldName = held.getType() == Material.AIR ? null : pretty(held.getType());

            Map<Material, Integer> totals = new EnumMap<>(Material.class);
            int slotsFree = 0;
            ItemStack[] storage = player.getInventory().getStorageContents();
            for (ItemStack stack : storage) {
                if (stack == null || stack.getType() == Material.AIR) {
                    slotsFree++;
                    continue;
                }
                totals.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }

            Material hoard = null;
            int hoardCount = 0;
            for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
                if (entry.getValue() > hoardCount) {
                    hoard = entry.getKey();
                    hoardCount = entry.getValue();
                }
            }

            // ---- Where they are standing ----
            int y = loc.getBlockY();
            boolean underground = world.getHighestBlockYAt(loc) > y + 2;
            int light = loc.getBlock().getLightFromBlocks();
            boolean night = world.getTime() >= 13000;

            int monsters = 0;
            for (var entity : player.getNearbyEntities(MONSTER_RADIUS, 8, MONSTER_RADIUS)) {
                if (entity instanceof Monster) monsters++;
            }

            return new Observation(
                    heldName,
                    hoard == null ? null : pretty(hoard),
                    hoardCount,
                    slotsFree,
                    player.getLevel(),
                    biomeName(loc),
                    dimensionName(world),
                    y,
                    underground,
                    light,
                    night,
                    world.isThundering(),
                    homeDistance(player, loc),
                    monsters);
        } catch (Exception e) {
            // An idle remark is the least important thing the plugin does.
            // It never gets to break a tick.
            return null;
        }
    }

    /**
     * Metres to the bed they will wake up in, falling back to world spawn.
     * Negative when the answer is in another world, which is not a distance.
     */
    private static double homeDistance(Player player, Location from) {
        Location home = player.getRespawnLocation();
        if (home == null) home = from.getWorld().getSpawnLocation();
        if (home.getWorld() != from.getWorld()) return -1.0;
        return home.distance(from);
    }

    /** "DEEPSLATE_IRON_ORE" -> "deepslate iron ore". */
    static String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String biomeName(Location loc) {
        try {
            return loc.getBlock().getBiome().getKey().getKey();
        } catch (Throwable t) {
            // Biome moved from enum to registry interface across versions;
            // the string form is good enough to put in a sentence.
            return String.valueOf(loc.getBlock().getBiome()).toLowerCase(Locale.ROOT);
        }
    }

    private static String dimensionName(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "the_end";
            default -> "normal";
        };
    }
}
