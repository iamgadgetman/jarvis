package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Farmer (v0.7.0) - "Farm the carrots, Jarvis."
 *
 * Two modes:
 * - Sweep (/jarvis farm [crop]): one pass over the field — walk to each
 *   mature crop, hoe swing, harvest, replant from the seeds he just
 *   collected, report the haul, done.
 * - Tend (/jarvis tend [crop]): the standing farmhand — same loop, but he
 *   stays, rescanning as crops mature, delivering to the deposit chest when
 *   his bags fill, until told to stop.
 *
 * Replanting is honest: seeds come from what he harvests (wheat drops
 * seeds, carrots drop carrots...). No seed in the bags = that farmland
 * stays bare and he says so once.
 */
class Farmer {

    /** Crop block -> the item planted to regrow it. */
    private static final Map<Material, Material> CROPS = new LinkedHashMap<>();
    static {
        CROPS.put(Material.WHEAT, Material.WHEAT_SEEDS);
        CROPS.put(Material.CARROTS, Material.CARROT);
        CROPS.put(Material.POTATOES, Material.POTATO);
        CROPS.put(Material.BEETROOTS, Material.BEETROOT_SEEDS);
        CROPS.put(Material.NETHER_WART, Material.NETHER_WART);
    }
    // Harvest-only (no replant onto stems — the stem regrows them)
    private static final Map<Material, Material> GOURDS = Map.of(
            Material.MELON, Material.AIR,
            Material.PUMPKIN, Material.AIR);

    static Material cropFromKeyword(String keyword) {
        if (keyword == null) return null;
        String k = keyword.toLowerCase();
        if (k.contains("wheat")) return Material.WHEAT;
        if (k.contains("carrot")) return Material.CARROTS;
        if (k.contains("potato")) return Material.POTATOES;
        if (k.contains("beet")) return Material.BEETROOTS;
        if (k.contains("wart")) return Material.NETHER_WART;
        if (k.contains("melon")) return Material.MELON;
        if (k.contains("pumpkin")) return Material.PUMPKIN;
        return null;
    }

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final NPC npc;
    private final DepositManager deposits;
    private final Material cropFilter;      // null = all crops
    private final boolean tendMode;
    private final Location fieldCenter;

    private final ArrayDeque<Location> targets = new ArrayDeque<>();
    private boolean working = false;
    private int harvested = 0;
    private int unplanted = 0;
    private boolean warnedSeeds = false;
    private int idleRescans = 0;

    private final int fieldRadius;
    private static final double REACH = 2.6;
    private static final int STALL_HOP_TICKS = 6;
    private int stalled = 0;
    private Location lastPos = null;

    Farmer(JarvisNPC host, Player player, NPC npc, DepositManager deposits,
           Material cropFilter, boolean tendMode) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.npc = npc;
        this.deposits = deposits;
        this.cropFilter = cropFilter;
        this.tendMode = tendMode;
        this.fieldCenter = host.getCurrentLocation(npc).getBlock().getLocation();
        this.fieldRadius = plugin.getConfig().getInt("farming.field-radius", 16);
    }

    void start() {
        host.applyNavigatorDefaults(npc, null);
        host.equipTool(npc, Material.DIAMOND_HOE);

        scanField();
        if (targets.isEmpty() && !tendMode) {
            host.say(player, "Nothing here is ready for harvest, sir. Patience is a virtue.");
            return;
        }

        String what = cropFilter != null
                ? cropFilter.name().toLowerCase().replace("_", " ") : "the crops";
        host.say(player, tendMode
                ? "I shall tend " + what + " until you say otherwise, sir."
                : "Harvesting " + what + ", sir — " + targets.size() + " ready.");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    return;
                }
                Location loc = host.getCurrentLocation(npc);
                host.pickupNearbyItems(npc, loc);
                tick(loc, this);
            }
        };
        task.runTaskTimer(plugin, 10L, 10L);
        host.registerTask(player, task);
    }

    private void tick(Location loc, BukkitRunnable self) {
        if (working) return;

        // Bags full? Deliver and continue (tend) or wrap up (sweep)
        if (host.lootSlotsUsed(npc) >= JarvisNPC.LOOT_CAPACITY - 2 && deposits.hasChest(player)) {
            host.say(player, "Bags full, sir — delivering the produce. Back shortly.");
            Location resume = loc.getBlock().getLocation();
            self.cancel();
            deposits.startDepositRun(player, npc, deposits.getChest(player), () -> {
                host.applyNavigatorDefaults(npc, null);
                host.equipTool(npc, Material.DIAMOND_HOE);
                start(); // re-enter: rescan from the field
            });
            return;
        }

        if (targets.isEmpty()) {
            if (!tendMode) {
                finish();
                self.cancel();
                return;
            }
            // Tend mode: idle at the field, rescan every ~15s
            idleRescans++;
            if (idleRescans >= 30) {
                idleRescans = 0;
                scanField();
                if (!targets.isEmpty()) {
                    host.sayQuiet(player, targets.size() + " ready for harvest.");
                }
            }
            // Drift back to the field center if he wandered
            if (loc.distance(fieldCenter.clone().add(0.5, 0, 0.5)) > fieldRadius
                    && !npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(fieldCenter.clone().add(0.5, 1, 0.5));
            }
            return;
        }

        Location target = targets.peek();
        Block crop = target.getBlock();

        // Crop no longer valid (broken, already harvested, grew out of filter)
        if (!isMature(crop)) {
            targets.poll();
            return;
        }

        double dist = loc.distance(target.clone().add(0.5, 0.5, 0.5));
        if (dist > REACH) {
            // Walk over
            if (!npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(target.clone().add(0.5, 1, 0.5));
            }
            if (lastPos != null && loc.distance(lastPos) < 0.15) stalled++;
            else stalled = 0;
            lastPos = loc.clone();
            if (stalled > STALL_HOP_TICKS) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(host.findSafeNear(target.clone().add(0.5, 1, 0.5)),
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                stalled = 0;
            }
            return;
        }

        // Harvest: face, swing the hoe, break, replant
        targets.poll();
        working = true;
        npc.faceLocation(target.clone().add(0.5, 0.5, 0.5));
        if (npc.getEntity() instanceof LivingEntity le) le.swingMainHand();

        Material type = crop.getType();
        crop.breakNaturally(host.getToolInHand(npc));
        harvested++;
        loc.getWorld().playSound(target, Sound.BLOCK_CROP_BREAK, 0.8f, 1.0f);

        // Replant after the drops have spawned and been swept
        Bukkit_runLater(() -> {
            replant(crop, type);
            working = false;
        }, 8L);
    }

    private void Bukkit_runLater(Runnable r, long ticks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, r, ticks);
    }

    private void replant(Block where, Material cropType) {
        if (GOURDS.containsKey(cropType)) return; // stems regrow melons/pumpkins

        Material seed = CROPS.get(cropType);
        if (seed == null) return;

        Block below = where.getRelative(BlockFace.DOWN);
        boolean soilOk = cropType == Material.NETHER_WART
                ? below.getType() == Material.SOUL_SAND
                : below.getType() == Material.FARMLAND;
        if (!soilOk || where.getType() != Material.AIR) return;

        if (!consumeSeed(seed)) {
            unplanted++;
            if (!warnedSeeds) {
                warnedSeeds = true;
                host.sayQuiet(player, "Out of seed for replanting — I'll plant what I harvest.");
            }
            return;
        }

        where.setType(cropType);
        if (where.getBlockData() instanceof Ageable age) {
            age.setAge(0);
            where.setBlockData(age);
        }
        where.getWorld().playSound(where.getLocation(), Sound.ITEM_CROP_PLANT, 0.7f, 1.0f);
    }

    /** Take one seed item from the bags (slots 1..35). */
    private boolean consumeSeed(Material seed) {
        Inventory invTrait = npc.getOrAddTrait(Inventory.class);
        ItemStack[] contents = invTrait.getContents();
        for (int i = 1; i < Math.min(36, contents.length); i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != seed) continue;
            if (item.getAmount() <= 1) contents[i] = null;
            else item.setAmount(item.getAmount() - 1);
            invTrait.setContents(contents);
            return true;
        }
        return false;
    }

    // ==================== FIELD SCAN ====================

    private void scanField() {
        targets.clear();
        World world = fieldCenter.getWorld();
        if (world == null) return;
        int cx = fieldCenter.getBlockX(), cy = fieldCenter.getBlockY(), cz = fieldCenter.getBlockZ();

        for (int x = -fieldRadius; x <= fieldRadius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -fieldRadius; z <= fieldRadius; z++) {
                    Block b = world.getBlockAt(cx + x, cy + y, cz + z);
                    if (isMature(b)) {
                        targets.add(b.getLocation());
                    }
                }
            }
        }
    }

    private boolean isMature(Block b) {
        Material type = b.getType();
        if (cropFilter != null && type != cropFilter) return false;

        if (GOURDS.containsKey(type)) return cropFilter == null || type == cropFilter;
        if (!CROPS.containsKey(type)) return false;
        return b.getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge();
    }

    private void finish() {
        npc.getNavigator().cancelNavigation();
        String seedNote = unplanted > 0
                ? " (" + unplanted + " plots await seed, I'm afraid.)" : " All replanted.";
        host.say(player, "Harvest complete, sir — " + harvested + " crops gathered." + seedNote);
        if (harvested >= 10) {
            Entertainer.celebrate(host, player, npc);
        }
        if (plugin.getConfig().getBoolean("mining.auto-deposit", true)
                && deposits.hasChest(player) && host.lootSlotsUsed(npc) > 0) {
            deposits.startDepositRun(player, npc, deposits.getChest(player), () -> {});
        }
    }
}
