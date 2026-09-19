package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * UIManager — the bell menu.
 *
 * <p>The menu used to reach fourteen of Jarvis's forty-odd capabilities;
 * everything else was command-only, which meant the GUI was a shortcut for
 * mining and nothing else. This exposes the rest: groundskeeping, the guard
 * stances, patrol, the schematic library, the household services and the
 * steward.
 *
 * <p>Two structural changes came with it. Menus are identified by a
 * {@link JarvisMenu} holder rather than by comparing title strings, and items
 * are built against live state — a greyed "Deposit" when no chest is
 * registered says more than a button that fails when pressed.
 *
 * <p>Anything whose output is chat (the report, the duty list, AI status) is
 * dispatched through {@code performCommand} rather than reimplemented, so the
 * permission checks and formatting stay in one place.
 */
public class UIManager implements Listener {

    private static final int PER_PAGE = 45;   // schematic picker: rows 1-5

    private final Jarvis plugin;

    public UIManager(Jarvis plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ==================== BUILDERS ====================

    private Inventory menu(JarvisMenu.Type type, int rows, String title) {
        return menu(new JarvisMenu(type), rows, title);
    }

    private Inventory menu(JarvisMenu holder, int rows, String title) {
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inv);
        return inv;
    }

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A disabled-looking entry: grey, with the reason it can't be used. */
    private ItemStack unavailable(String name, String reason) {
        return item(Material.GRAY_DYE, ChatColor.DARK_GRAY + ChatColor.stripColor(name),
                ChatColor.GRAY + reason);
    }

    private ItemStack back() {
        return item(Material.ARROW, ChatColor.WHITE + "Back", ChatColor.GRAY + "Return to the main menu");
    }

    private void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane);
        }
    }

    // ==================== MAIN MENU ====================

    private Inventory createMainMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.MAIN, 6, "Jarvis");
        var npc  = plugin.getJarvisNPC();
        boolean summoned = npc.getNPCForPlayer(p.getUniqueId()) != null;
        var deposit = npc.getDepositManager();

        // Row 1 — presence and pockets
        if (summoned) {
            m.setItem(0, item(Material.BARRIER, ChatColor.RED + "Dismiss",
                    ChatColor.GRAY + "Send Jarvis away", ChatColor.GRAY + "Items are kept"));
        } else {
            m.setItem(0, item(Material.ARMOR_STAND, ChatColor.GREEN + "Summon",
                    ChatColor.GRAY + "Call Jarvis to your side"));
        }
        m.setItem(1, item(Material.COMPASS, ChatColor.AQUA + "Come here",
                ChatColor.GRAY + "Recall him to you"));
        m.setItem(2, item(Material.LEAD, ChatColor.GREEN + "Follow",
                ChatColor.GRAY + "Stay close and carry loot"));
        m.setItem(3, item(Material.REDSTONE_BLOCK, ChatColor.RED + "Stop",
                ChatColor.GRAY + "Halt whatever he's doing"));

        m.setItem(5, item(Material.CHEST, ChatColor.GOLD + "Loot",
                ChatColor.GRAY + "Open what he's collected"));
        if (deposit.hasChest(p)) {
            m.setItem(6, item(Material.HOPPER, ChatColor.YELLOW + "Deposit",
                    ChatColor.GRAY + "Deliver loot to your chest"));
        } else {
            m.setItem(6, unavailable("Deposit", "No chest registered yet"));
        }
        m.setItem(7, item(Material.ENDER_CHEST, ChatColor.YELLOW + "Set deposit chest",
                ChatColor.GRAY + "Registers the chest you're looking at"));
        m.setItem(8, item(Material.LAVA_BUCKET, ChatColor.RED + "Clear loot",
                ChatColor.GRAY + "Drop everything he carries",
                ChatColor.DARK_GRAY + "Asks first"));

        // Row 3 — the departments
        m.setItem(19, item(Material.NETHERITE_PICKAXE, ChatColor.AQUA + "Mining",
                ChatColor.GRAY + "Ores, branch mines, shafts"));
        m.setItem(20, item(Material.NETHERITE_SWORD, ChatColor.RED + "Combat & Guard",
                ChatColor.GRAY + "Stances, night watch, patrol"));
        m.setItem(21, item(Material.WHEAT, ChatColor.GREEN + "Groundskeeping",
                ChatColor.GRAY + "Farm, chop, fish, light the place"));
        m.setItem(22, item(Material.BRICKS, ChatColor.GOLD + "Building",
                ChatColor.GRAY + "The schematic library"));
        m.setItem(23, item(Material.CLOCK, ChatColor.LIGHT_PURPLE + "Household",
                ChatColor.GRAY + "Home, escort, death drops"));
        m.setItem(24, item(Material.BOOK, ChatColor.YELLOW + "Steward",
                ChatColor.GRAY + "Briefing, standing duties"));
        var progression = plugin.getProgressionManager();
        if (progression != null && progression.isEnabled()) {
            var rank = progression.rankOf(p);
            m.setItem(26, item(Material.GOLDEN_HELMET, ChatColor.GOLD + "Service record",
                    ChatColor.GRAY + "Rank " + rank.number() + " — " + rank.title(),
                    ChatColor.DARK_GRAY + "What he's earned, and what's next"));
        }
        m.setItem(25, item(Material.REDSTONE, ChatColor.LIGHT_PURPLE + "Settings",
                ChatColor.GRAY + "Torches, pickup range, returns"));

        // Row 5 — status, and it is clickable when there is something to stop
        String task = npc.describeCurrentTask(p.getUniqueId());
        if (!summoned) {
            m.setItem(40, item(Material.GRAY_DYE, ChatColor.GRAY + "Not summoned",
                    ChatColor.DARK_GRAY + "Summon him to begin"));
        } else if (task != null) {
            m.setItem(40, item(Material.LIME_DYE, ChatColor.GREEN + task,
                    ChatColor.GRAY + "Click to stop"));
        } else {
            m.setItem(40, item(Material.PAPER, ChatColor.WHITE + "Awaiting orders",
                    ChatColor.GRAY + "Summoned and idle"));
        }

        if (p.hasPermission("jarvis.admin")) {
            m.setItem(45, item(Material.COMMAND_BLOCK, ChatColor.DARK_RED + "Admin",
                    ChatColor.GRAY + "Requests, reload, AI health"));
        }
        m.setItem(53, item(Material.OAK_DOOR, ChatColor.WHITE + "Close"));

        fill(m);
        return m;
    }

    // ==================== MINING ====================

    private Inventory createMiningMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.MINING, 4, "Jarvis — Mining");
        var cfg = plugin.getConfig();

        m.setItem(10, item(Material.DIAMOND_ORE, ChatColor.AQUA + "Mine ores",
                ChatColor.GRAY + "Find and follow nearby veins"));
        m.setItem(11, item(Material.RAIL, ChatColor.GOLD + "Branch mine",
                ChatColor.GRAY + "Dig a lit branch pattern here"));
        m.setItem(12, item(Material.LADDER, ChatColor.YELLOW + "Dig a shaft",
                ChatColor.GRAY + "Straight down, safely"));
        m.setItem(13, item(Material.REDSTONE_BLOCK, ChatColor.RED + "Stop",
                ChatColor.GRAY + "Halt the current dig"));

        var prog = plugin.getProgressionManager();
        boolean canTunnel = prog == null
                || prog.has(p, com.gadgetman.jarvis.progression.Rank.Capability.WIDE_BORE);
        if (canTunnel) {
            m.setItem(9, item(Material.NETHERITE_PICKAXE, ChatColor.GOLD + "Drive a tunnel",
                    ChatColor.GRAY + "A straight 3x3 passage where he faces",
                    ChatColor.DARK_GRAY + "Length: "
                            + cfg.getInt("mining.tunnel.default-length", 32)));
        } else {
            m.setItem(9, unavailable("Drive a tunnel",
                    "Unlocked at " + com.gadgetman.jarvis.progression.Rank.PEERLESS.title()));
        }

        boolean torches = cfg.getBoolean("mining.place-torches", false);
        m.setItem(15, item(torches ? Material.TORCH : Material.COAL,
                ChatColor.YELLOW + "Torches: " + onOff(torches),
                ChatColor.GRAY + "Light the tunnel as he goes",
                ChatColor.DARK_GRAY + "Click to toggle"));
        m.setItem(16, item(Material.LANTERN,
                ChatColor.GOLD + "Torch spacing: " + cfg.getInt("mining.torch-spacing", 8),
                ChatColor.GRAY + "Blocks between torches",
                ChatColor.DARK_GRAY + "Left +1  ·  Right -1"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== COMBAT & GUARD ====================

    private Inventory createCombatMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.COMBAT, 4, "Jarvis — Combat & Guard");
        var deposit = plugin.getJarvisNPC().getDepositManager();

        m.setItem(10, item(Material.SHIELD, ChatColor.BLUE + "Stance: Passive",
                ChatColor.GRAY + "Stay by you, never swing first"));
        m.setItem(11, item(Material.IRON_SWORD, ChatColor.YELLOW + "Stance: Defensive",
                ChatColor.GRAY + "Answer anything that attacks you"));
        m.setItem(12, item(Material.NETHERITE_SWORD, ChatColor.RED + "Stance: Aggressive",
                ChatColor.GRAY + "Hunt hostiles on sight"));

        m.setItem(14, item(Material.CAMPFIRE, ChatColor.GOLD + "Night watch",
                ChatColor.GRAY + "Hold this spot and keep guard"));

        int points = deposit.getPatrol(p).size();
        m.setItem(15, item(Material.OAK_SIGN, ChatColor.AQUA + "Patrol: add waypoint",
                ChatColor.GRAY + "Marks where you're standing",
                ChatColor.DARK_GRAY + "Waypoints so far: " + points));
        if (points >= 2) {
            m.setItem(16, item(Material.MAP, ChatColor.GREEN + "Patrol: start",
                    ChatColor.GRAY + "Walk the " + points + "-point circuit"));
        } else {
            m.setItem(16, unavailable("Patrol: start", "Needs at least two waypoints"));
        }
        m.setItem(17, item(Material.BARRIER, ChatColor.RED + "Patrol: clear",
                ChatColor.GRAY + "Forget the route"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== GROUNDSKEEPING ====================

    private Inventory createGroundsMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.GROUNDSKEEPING, 4, "Jarvis — Groundskeeping");
        var cfg = plugin.getConfig();

        m.setItem(10, item(Material.WHEAT, ChatColor.GREEN + "Harvest once",
                ChatColor.GRAY + "Reap and replant the field, then stop"));
        m.setItem(11, item(Material.IRON_HOE, ChatColor.GREEN + "Tend the field",
                ChatColor.GRAY + "Stay on as a standing farmhand"));

        int trees = cfg.getInt("farming.chop-count", 5);
        m.setItem(12, item(Material.IRON_AXE, ChatColor.GOLD + "Fell " + trees + " trees",
                ChatColor.GRAY + "Chops and replants saplings",
                ChatColor.DARK_GRAY + "Left +1  ·  Right -1"));
        m.setItem(13, item(Material.FISHING_ROD, ChatColor.AQUA + "Go fishing",
                ChatColor.GRAY + "A spot of angling"));

        int radius = cfg.getInt("lighting.default-radius", 16);
        String type = cfg.getString("lighting.default-type", "torch");
        m.setItem(15, item(Material.TORCH, ChatColor.YELLOW + "Light the area",
                ChatColor.GRAY + "Spawn-proof " + radius + " blocks with " + type,
                ChatColor.DARK_GRAY + "Left +2 radius  ·  Right -2"));
        m.setItem(16, item(Material.SOUL_LANTERN, ChatColor.GOLD + "Light type: " + type,
                ChatColor.GRAY + "torch → lantern → end_rod",
                ChatColor.DARK_GRAY + "Click to cycle"));

        m.setItem(22, item(Material.JUKEBOX, ChatColor.LIGHT_PURPLE + "Dance",
                ChatColor.GRAY + "The performance"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== BUILDING ====================

    private Inventory createBuildingMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.BUILDING, 4, "Jarvis — Building");
        var sm = plugin.getSchematicManager();
        int count = sm == null ? 0 : sm.getSchematics().size();

        if (count > 0) {
            m.setItem(10, item(Material.BOOKSHELF, ChatColor.GOLD + "Schematic library",
                    ChatColor.GRAY + String.valueOf(count) + " available",
                    ChatColor.DARK_GRAY + "Click one to build it here"));
        } else {
            m.setItem(10, unavailable("Schematic library", "No schematics in the folder"));
        }
        m.setItem(11, item(Material.SPYGLASS, ChatColor.AQUA + "Rescan folder",
                ChatColor.GRAY + "Pick up newly added files"));
        m.setItem(12, item(Material.BARRIER, ChatColor.RED + "Cancel build",
                ChatColor.GRAY + "Stop the build in progress"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    private Inventory createSchematicPicker(Player p, int page) {
        var sm = plugin.getSchematicManager();
        List<String> names = new ArrayList<>();
        if (sm != null) {
            for (var info : sm.getSchematics()) names.add(info.name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);

        int pages = Math.max(1, (int) Math.ceil(names.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        Inventory m = menu(new JarvisMenu(JarvisMenu.Type.SCHEMATIC_PICKER, page), 6,
                "Schematics (" + (page + 1) + "/" + pages + ")");

        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < names.size(); i++) {
            String name = names.get(from + i);
            m.setItem(i, item(Material.PAPER, ChatColor.WHITE + name,
                    ChatColor.GRAY + "Build this at your position"));
        }

        if (page > 0) {
            m.setItem(45, item(Material.ARROW, ChatColor.WHITE + "Previous page"));
        }
        if (page < pages - 1) {
            m.setItem(53, item(Material.ARROW, ChatColor.WHITE + "Next page"));
        }
        m.setItem(49, back());
        return m;   // deliberately not filled — the grid reads better empty
    }

    // ==================== HOUSEHOLD ====================

    private Inventory createHouseholdMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.HOUSEHOLD, 4, "Jarvis — Household");
        var npc = plugin.getJarvisNPC();
        var deposit = npc.getDepositManager();

        m.setItem(10, item(Material.RED_BED, ChatColor.LIGHT_PURPLE + "Set home",
                ChatColor.GRAY + "Remember this spot as home"));
        if (deposit.getHome(p) != null) {
            m.setItem(11, item(Material.COMPASS, ChatColor.AQUA + "Escort me home",
                    ChatColor.GRAY + "Jarvis walks you back"));
        } else {
            m.setItem(11, unavailable("Escort me home", "No home set yet"));
        }

        if (npc.getRecoveryService().hasDeathPoint(p)) {
            m.setItem(12, item(Material.TOTEM_OF_UNDYING, ChatColor.GOLD + "Recover my drops",
                    ChatColor.GRAY + "Fetch what you left where you died"));
        } else {
            m.setItem(12, unavailable("Recover my drops", "Nothing to recover"));
        }

        m.setItem(14, item(Material.ENDER_CHEST, ChatColor.YELLOW + "Set deposit chest",
                ChatColor.GRAY + "Registers the chest you're looking at"));
        if (deposit.hasChest(p)) {
            m.setItem(15, item(Material.HOPPER, ChatColor.YELLOW + "Deposit now",
                    ChatColor.GRAY + "Deliver loot to your chest"));
        } else {
            m.setItem(15, unavailable("Deposit now", "No chest registered yet"));
        }

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== STEWARD ====================

    private Inventory createStewardMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.STEWARD, 4, "Jarvis — Steward");

        m.setItem(11, item(Material.WRITTEN_BOOK, ChatColor.GOLD + "Briefing",
                ChatColor.GRAY + "The server status report"));
        m.setItem(12, item(Material.CLOCK, ChatColor.AQUA + "Standing duties",
                ChatColor.GRAY + "What he does on a schedule"));
        m.setItem(13, item(Material.AMETHYST_SHARD, ChatColor.LIGHT_PURPLE + "AI status",
                ChatColor.GRAY + "Routing and provider health"));
        m.setItem(14, item(Material.NAME_TAG, ChatColor.WHITE + "Version",
                ChatColor.GRAY + "What is actually running"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== SERVICE RECORD ====================

    private Inventory createServiceRecord(Player p) {
        Inventory m = menu(JarvisMenu.Type.SERVICE_RECORD, 6, "Jarvis — Service Record");
        var progression = plugin.getProgressionManager();
        var record = progression.recordOf(p);
        var rank   = progression.rankOf(p);
        boolean exempt = progression.isExempt(p);

        m.setItem(4, item(Material.GOLDEN_HELMET,
                ChatColor.GOLD + "Rank " + rank.number() + " — " + rank.title(),
                exempt ? ChatColor.LIGHT_PURPLE + "Operator: top kit, no climb required"
                       : ChatColor.GRAY + "Service: " + String.valueOf(record.service()),
                ChatColor.DARK_GRAY + "Current kit: " + kitLine(rank)));

        // What he has actually done
        m.setItem(19, tally(Material.DIAMOND_ORE, "Ore mined",  record.oresMined()));
        m.setItem(20, tally(Material.OAK_LOG,     "Trees felled", record.treesFelled()));
        m.setItem(21, tally(Material.WHEAT,       "Crops harvested", record.cropsHarvested()));
        m.setItem(22, tally(Material.COD,         "Fish landed", record.fishCaught()));
        m.setItem(23, tally(Material.ROTTEN_FLESH,"Threats felled", record.threatsFelled()));
        m.setItem(24, tally(Material.BRICKS,      "Blocks laid", record.blocksPlaced()));

        // The ladder, so there is something to aim at
        var ranks = com.gadgetman.jarvis.progression.Rank.values();
        for (int i = 0; i < ranks.length && i < 9; i++) {
            var r = ranks[i];
            boolean earned = r.ordinal() <= rank.ordinal();
            m.setItem(36 + i, item(
                    earned ? Material.LIME_DYE : Material.GRAY_DYE,
                    (earned ? ChatColor.GREEN : ChatColor.DARK_GRAY) + r.title(),
                    ChatColor.GRAY + "Rank " + r.number(),
                    ChatColor.DARK_GRAY + (earned ? "Earned" : "At " + r.serviceRequired() + " service"),
                    ChatColor.WHITE + r.whatIsNew()));
        }

        var next = rank.next();
        if (exempt) {
            m.setItem(49, item(Material.NETHERITE_INGOT, ChatColor.LIGHT_PURPLE + "Operator",
                    ChatColor.GRAY + "You are issued the top kit regardless.",
                    ChatColor.DARK_GRAY + "Swap his tools freely — they won't be overwritten."));
        } else if (next != null) {
            m.setItem(49, item(Material.EXPERIENCE_BOTTLE,
                    ChatColor.AQUA + "Next: " + next.title(),
                    ChatColor.GRAY + String.valueOf(record.serviceToNext()) + " more service",
                    ChatColor.WHITE + "Brings: " + next.whatIsNew()));
        } else {
            m.setItem(49, item(Material.NETHERITE_INGOT, ChatColor.GOLD + "Nothing left to earn",
                    ChatColor.GRAY + "He is as good as he gets, sir."));
        }

        m.setItem(45, back());
        fill(m);
        return m;
    }

    private ItemStack tally(Material icon, String label, int count) {
        return item(icon, ChatColor.WHITE + label, ChatColor.GRAY + String.valueOf(count));
    }

    private String kitLine(com.gadgetman.jarvis.progression.Rank rank) {
        StringBuilder sb = new StringBuilder(
                rank.toolFor(com.gadgetman.jarvis.progression.Rank.ToolKind.PICKAXE)
                        .name().split("_")[0].toLowerCase());
        for (var e : rank.enchants().entrySet()) {
            sb.append(", ").append(com.gadgetman.jarvis.progression.Rank.pretty(e.getKey()))
              .append(' ').append(com.gadgetman.jarvis.progression.Rank.roman(e.getValue()));
        }
        return sb.toString();
    }

    private void onServiceRecord(Player p, int slot) {
        if (slot == 45) open(p, createMainMenu(p));
    }

    // ==================== SETTINGS ====================

    private Inventory createSettingsMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.SETTINGS, 4, "Jarvis — Settings");
        var cfg = plugin.getConfig();

        boolean torches = cfg.getBoolean("mining.place-torches", false);
        m.setItem(10, item(torches ? Material.TORCH : Material.COAL,
                ChatColor.YELLOW + "Torch placement: " + onOff(torches),
                ChatColor.DARK_GRAY + "Click to toggle"));

        m.setItem(11, item(Material.ENDER_PEARL,
                ChatColor.AQUA + "Pickup range: " + cfg.getInt("mining.pickup-radius", 8),
                ChatColor.GRAY + "How far he'll step for a dropped item",
                ChatColor.DARK_GRAY + "Left +1  ·  Right -1"));

        boolean autoReturn = cfg.getBoolean("mining.auto-return", true);
        m.setItem(12, item(autoReturn ? Material.ENDER_EYE : Material.ENDER_PEARL,
                ChatColor.LIGHT_PURPLE + "Auto-return: " + onOff(autoReturn),
                ChatColor.GRAY + "Come back when stuck or full",
                ChatColor.DARK_GRAY + "Click to toggle"));

        m.setItem(14, item(Material.LANTERN,
                ChatColor.GOLD + "Torch spacing: " + cfg.getInt("mining.torch-spacing", 8),
                ChatColor.DARK_GRAY + "Left +1  ·  Right -1"));

        m.setItem(15, item(Material.NOTE_BLOCK,
                ChatColor.GREEN + "Vein mining: " + onOff(cfg.getBoolean("mining.enable-vein-mining", true)),
                ChatColor.GRAY + "Follow a whole vein once he finds it",
                ChatColor.DARK_GRAY + "Click to toggle"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== ADMIN ====================

    private Inventory createAdminMenu(Player p) {
        Inventory m = menu(JarvisMenu.Type.ADMIN, 4, "Jarvis — Admin");

        m.setItem(11, item(Material.WRITABLE_BOOK, ChatColor.GOLD + "Pending requests",
                ChatColor.GRAY + "Item requests awaiting a decision"));
        m.setItem(12, item(Material.AMETHYST_SHARD, ChatColor.LIGHT_PURPLE + "AI status",
                ChatColor.GRAY + "Routing and provider health"));
        m.setItem(13, item(Material.REPEATER, ChatColor.AQUA + "Reload config",
                ChatColor.GRAY + "Re-read config.yml"));
        m.setItem(14, item(Material.CHEST_MINECART, ChatColor.YELLOW + "Export dataset",
                ChatColor.GRAY + "Dump intent & build pairs as JSONL"));

        m.setItem(31, back());
        fill(m);
        return m;
    }

    // ==================== CONFIRM ====================

    private Inventory createConfirmClearMenu() {
        Inventory m = menu(JarvisMenu.Type.CONFIRM_CLEAR, 3, "Drop everything he carries?");
        m.setItem(4, item(Material.PAPER, ChatColor.YELLOW + "This drops ALL collected items",
                ChatColor.GRAY + "at Jarvis's current position.",
                ChatColor.RED + "It cannot be undone."));
        m.setItem(11, item(Material.GREEN_WOOL, ChatColor.GREEN + "Confirm"));
        m.setItem(15, item(Material.RED_WOOL, ChatColor.RED + "Cancel"));
        fill(m);
        return m;
    }

    // ==================== HELPERS ====================

    private String onOff(boolean b) {
        return b ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF";
    }

    /** Close the menu and run a slash command, so permissions stay in one place. */
    private void run(Player p, String command) {
        p.closeInventory();
        p.performCommand(command);
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    private void open(Player p, Inventory inv) {
        p.openInventory(inv);
        click(p);
    }

    private boolean isControllerBell(ItemStack item) {
        if (item == null || item.getType() != Material.BELL) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
                plugin.getControllerKey(), PersistentDataType.BYTE);
    }

    /** The menu permission was declared in plugin.yml but never actually checked. */
    private boolean mayUseMenu(Player p) {
        if (p.hasPermission("jarvis.menu.use")) return true;
        p.sendMessage(ChatColor.RED + "Jarvis: You aren't permitted to use my controls, sir.");
        return false;
    }

    public void openMainMenu(Player p) {
        if (!mayUseMenu(p)) return;
        p.openInventory(createMainMenu(p));
    }

    // ==================== EVENTS ====================

    @EventHandler
    public void onBellUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        // Held bell, right-clicked at air or a block
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                && isControllerBell(item) && e.getHand() == EquipmentSlot.HAND) {
            e.setCancelled(true);
            if (!mayUseMenu(p)) return;
            p.openInventory(createMainMenu(p));
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            return;
        }

        // A placed controller bell
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType() == Material.BELL) {
            Block b = e.getClickedBlock();
            if (!(b.getState() instanceof TileState ts)) return;
            if (ts.getPersistentDataContainer().has(plugin.getControllerKey(), PersistentDataType.BYTE)) {
                e.setCancelled(true);
                if (!mayUseMenu(p)) return;
                p.openInventory(createMainMenu(p));
                p.playSound(b.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            }
        }
    }

    @EventHandler
    public void onBellPlace(BlockPlaceEvent e) {
        if (!isControllerBell(e.getItemInHand())) return;
        Block placed = e.getBlockPlaced();
        if (placed.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(
                    plugin.getControllerKey(), PersistentDataType.BYTE, (byte) 1);
            state.update();
        }
    }

    @EventHandler
    public void onRightClickNPC(NPCRightClickEvent e) {
        Player p = e.getClicker();
        NPC npc = plugin.getJarvisNPC().getNPCForPlayer(p.getUniqueId());
        if (npc != null && e.getNPC().equals(npc)) {
            e.setCancelled(true);
            if (!mayUseMenu(p)) return;
            p.openInventory(createMainMenu(p));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof JarvisMenu holder)) return;

        // Every click in one of our views is ours — including the player's own
        // inventory rows, so nothing can be dragged in or shift-clicked out.
        e.setCancelled(true);
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) return;

        switch (holder.getType()) {
            case MAIN             -> onMain(p, e.getRawSlot());
            case MINING           -> onMining(p, e);
            case COMBAT           -> onCombat(p, e.getRawSlot());
            case GROUNDSKEEPING   -> onGrounds(p, e);
            case BUILDING         -> onBuilding(p, e.getRawSlot());
            case SCHEMATIC_PICKER -> onSchematicPicker(p, e.getRawSlot(), holder.getPage());
            case HOUSEHOLD        -> onHousehold(p, e.getRawSlot());
            case STEWARD          -> onSteward(p, e.getRawSlot());
            case SERVICE_RECORD   -> onServiceRecord(p, e.getRawSlot());
            case SETTINGS         -> onSettings(p, e);
            case ADMIN            -> onAdmin(p, e.getRawSlot());
            case CONFIRM_CLEAR    -> onConfirmClear(p, e.getRawSlot());
        }
    }

    // ==================== CLICK HANDLERS ====================

    private void onMain(Player p, int slot) {
        var npc = plugin.getJarvisNPC();
        boolean summoned = npc.getNPCForPlayer(p.getUniqueId()) != null;

        switch (slot) {
            case 0 -> { if (summoned) npc.dismiss(p); else npc.summon(p); p.closeInventory(); }
            case 1 -> { npc.returnToPlayer(p); p.closeInventory(); }
            case 2 -> { npc.follow(p); p.closeInventory(); }
            case 3 -> { npc.stop(p); p.closeInventory(); }
            case 5 -> npc.openInventory(p);
            case 6 -> { if (npc.getDepositManager().hasChest(p)) { npc.getDepositManager().deposit(p); p.closeInventory(); } }
            case 7 -> { npc.getDepositManager().setChest(p); p.closeInventory(); }
            case 8 -> open(p, createConfirmClearMenu());
            case 19 -> open(p, createMiningMenu(p));
            case 20 -> open(p, createCombatMenu(p));
            case 21 -> open(p, createGroundsMenu(p));
            case 22 -> open(p, createBuildingMenu(p));
            case 23 -> open(p, createHouseholdMenu(p));
            case 24 -> open(p, createStewardMenu(p));
            case 25 -> open(p, createSettingsMenu(p));
            case 26 -> { if (plugin.getProgressionManager() != null) open(p, createServiceRecord(p)); }
            case 40 -> { // status: click to stop whatever is running
                if (summoned && npc.describeCurrentTask(p.getUniqueId()) != null) {
                    npc.stop(p);
                    p.closeInventory();
                }
            }
            case 45 -> { if (p.hasPermission("jarvis.admin")) open(p, createAdminMenu(p)); }
            case 53 -> p.closeInventory();
            default -> { }
        }
    }

    private void onMining(Player p, InventoryClickEvent e) {
        var npc = plugin.getJarvisNPC();
        var cfg = plugin.getConfig();
        switch (e.getRawSlot()) {
            case 9 -> { npc.tunnel(p, 0); p.closeInventory(); }
            case 10 -> { npc.mine(p); p.closeInventory(); }
            case 11 -> { npc.startBranchMining(p); p.closeInventory(); }
            case 12 -> { npc.digDown(p, 0); p.closeInventory(); }
            case 13 -> { npc.stop(p); p.closeInventory(); }
            case 15 -> {
                boolean now = !cfg.getBoolean("mining.place-torches", false);
                cfg.set("mining.place-torches", now);
                plugin.saveConfig();
                open(p, createMiningMenu(p));
            }
            case 16 -> {
                adjust(p, "mining.torch-spacing", 8, e.isLeftClick() ? 1 : -1, 3, 20);
                open(p, createMiningMenu(p));
            }
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onCombat(Player p, int slot) {
        var npc = plugin.getJarvisNPC();
        switch (slot) {
            case 10 -> { npc.guard(p, "passive");    p.closeInventory(); }
            case 11 -> { npc.guard(p, "defensive");  p.closeInventory(); }
            case 12 -> { npc.guard(p, "aggressive"); p.closeInventory(); }
            case 14 -> { npc.watch(p, null);         p.closeInventory(); }
            case 15 -> { npc.patrol(p, "add");   open(p, createCombatMenu(p)); }
            case 16 -> {
                if (npc.getDepositManager().getPatrol(p).size() >= 2) {
                    npc.patrol(p, "start");
                    p.closeInventory();
                }
            }
            case 17 -> { npc.patrol(p, "clear"); open(p, createCombatMenu(p)); }
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onGrounds(Player p, InventoryClickEvent e) {
        var npc = plugin.getJarvisNPC();
        var cfg = plugin.getConfig();
        switch (e.getRawSlot()) {
            case 10 -> { npc.farm(p, null, false); p.closeInventory(); }
            case 11 -> { npc.farm(p, null, true);  p.closeInventory(); }
            case 12 -> {
                if (e.isRightClick()) {
                    adjust(p, "farming.chop-count", 5, -1, 1, 32);
                    open(p, createGroundsMenu(p));
                } else {
                    npc.chop(p, cfg.getInt("farming.chop-count", 5));
                    p.closeInventory();
                }
            }
            case 13 -> { npc.fish(p); p.closeInventory(); }
            case 15 -> {
                if (e.isRightClick()) {
                    adjust(p, "lighting.default-radius", 16, -2, 4, 48);
                    open(p, createGroundsMenu(p));
                } else {
                    npc.light(p, -1, null, -1);
                    p.closeInventory();
                }
            }
            case 16 -> {
                String cur = cfg.getString("lighting.default-type", "torch");
                String next = switch (cur == null ? "torch" : cur.toLowerCase()) {
                    case "torch"   -> "lantern";
                    case "lantern" -> "end_rod";
                    default        -> "torch";
                };
                cfg.set("lighting.default-type", next);
                plugin.saveConfig();
                open(p, createGroundsMenu(p));
            }
            case 22 -> { npc.dance(p); p.closeInventory(); }
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onBuilding(Player p, int slot) {
        switch (slot) {
            case 10 -> {
                var sm = plugin.getSchematicManager();
                if (sm != null && !sm.getSchematics().isEmpty()) open(p, createSchematicPicker(p, 0));
            }
            case 11 -> run(p, "jarvis schematic scan");
            case 12 -> run(p, "jarvis cancelbuild");
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onSchematicPicker(Player p, int slot, int page) {
        if (slot == 49) { open(p, createBuildingMenu(p)); return; }
        if (slot == 45) { open(p, createSchematicPicker(p, page - 1)); return; }
        if (slot == 53) { open(p, createSchematicPicker(p, page + 1)); return; }
        if (slot < 0 || slot >= PER_PAGE) return;

        var sm = plugin.getSchematicManager();
        if (sm == null) return;
        List<String> names = new ArrayList<>();
        for (var info : sm.getSchematics()) names.add(info.name);
        names.sort(String.CASE_INSENSITIVE_ORDER);

        int index = page * PER_PAGE + slot;
        if (index >= names.size()) return;
        p.closeInventory();
        sm.pasteSchematic(p, names.get(index));
    }

    private void onHousehold(Player p, int slot) {
        var npc = plugin.getJarvisNPC();
        var deposit = npc.getDepositManager();
        switch (slot) {
            case 10 -> { npc.getEscortService().setHome(p); open(p, createHouseholdMenu(p)); }
            case 11 -> { if (deposit.getHome(p) != null) { npc.getEscortService().takeHome(p); p.closeInventory(); } }
            case 12 -> { if (npc.getRecoveryService().hasDeathPoint(p)) { npc.getRecoveryService().recover(p); p.closeInventory(); } }
            case 14 -> { deposit.setChest(p); open(p, createHouseholdMenu(p)); }
            case 15 -> { if (deposit.hasChest(p)) { deposit.deposit(p); p.closeInventory(); } }
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onSteward(Player p, int slot) {
        switch (slot) {
            case 11 -> run(p, "jarvis report");
            case 12 -> run(p, "jarvis duties");
            case 13 -> run(p, "jarvis ai");
            case 14 -> run(p, "jarvis version");
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onSettings(Player p, InventoryClickEvent e) {
        var cfg = plugin.getConfig();
        switch (e.getRawSlot()) {
            case 10 -> {
                cfg.set("mining.place-torches", !cfg.getBoolean("mining.place-torches", false));
                plugin.saveConfig();
                open(p, createSettingsMenu(p));
            }
            case 11 -> {
                adjust(p, "mining.pickup-radius", 8, e.isLeftClick() ? 1 : -1, 2, 16);
                open(p, createSettingsMenu(p));
            }
            case 12 -> {
                cfg.set("mining.auto-return", !cfg.getBoolean("mining.auto-return", true));
                plugin.saveConfig();
                open(p, createSettingsMenu(p));
            }
            case 14 -> {
                adjust(p, "mining.torch-spacing", 8, e.isLeftClick() ? 1 : -1, 3, 20);
                open(p, createSettingsMenu(p));
            }
            case 15 -> {
                cfg.set("mining.enable-vein-mining", !cfg.getBoolean("mining.enable-vein-mining", true));
                plugin.saveConfig();
                open(p, createSettingsMenu(p));
            }
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onAdmin(Player p, int slot) {
        if (!p.hasPermission("jarvis.admin")) return;
        switch (slot) {
            case 11 -> run(p, "jarvis requests");
            case 12 -> run(p, "jarvis ai");
            case 13 -> run(p, "jarvis reload");
            case 14 -> run(p, "jarvis export-dataset");
            case 31 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    private void onConfirmClear(Player p, int slot) {
        switch (slot) {
            case 11 -> {
                plugin.getJarvisNPC().clearInventory(p);
                p.closeInventory();
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.5f);
            }
            case 15 -> open(p, createMainMenu(p));
            default -> { }
        }
    }

    /** Nudge a numeric config value within bounds and persist it. */
    private void adjust(Player p, String path, int def, int delta, int min, int max) {
        int next = Math.max(min, Math.min(plugin.getConfig().getInt(path, def) + delta, max));
        plugin.getConfig().set(path, next);
        plugin.saveConfig();
    }
}
