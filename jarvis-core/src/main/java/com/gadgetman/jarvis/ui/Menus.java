package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.JarvisCore;
import com.gadgetman.jarvis.ai.AiSettings;
import com.gadgetman.jarvis.voice.VoiceConfig;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.platform.Subscription;
import com.gadgetman.jarvis.core.platform.events.ButlerInteractEvent;
import com.gadgetman.jarvis.core.platform.events.ItemUseEvent;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.ui.Menu;
import com.gadgetman.jarvis.core.ui.MenuClick;
import com.gadgetman.jarvis.core.ui.MenuItem;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.npc.DepositManager;
import com.gadgetman.jarvis.progression.ProgressionManager;
import com.gadgetman.jarvis.progression.Rank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The bell menu.
 *
 * <p>Every menu is a {@link Menu} model built against live state — a greyed
 * "Deposit" when no chest is registered says more than a button that fails
 * when pressed — and each item carries what to do when clicked. The adapter
 * draws them and reports the clicks; nothing here knows how a chest screen
 * looks.
 *
 * <p>Anything whose output is chat (the report, the duty list, AI status) is
 * dispatched through the command sink rather than reimplemented, so the
 * permission checks and formatting stay in one place.
 */
public class Menus {

    private static final int PER_PAGE = 45;   // schematic picker: rows 1-5

    private final JarvisCore core;
    private final Platform platform;
    private final List<Subscription> subscriptions = new ArrayList<>();

    public Menus(JarvisCore core) {
        this.core = core;
        this.platform = core.platform();
    }

    /** Listen for the bell and for a click on the butler. */
    public void start() {
        subscriptions.add(platform.events().on(ItemUseEvent.class, e -> {
            if (!ControllerBell.isController(e.item())) return;
            e.cancel().accept(true);
            if (!mayUseMenu(e.who())) return;
            platform.ui().open(e.who(), main(e.who()));
            e.who().sound(Ids.SOUND_BLOCK_BELL_USE, 1f, 1f);
        }));
        subscriptions.add(platform.events().on(ButlerInteractEvent.class, e -> {
            if (!e.ownerId().equals(e.clicker().id())) return;
            e.cancel().accept(true);
            if (!mayUseMenu(e.clicker())) return;
            platform.ui().open(e.clicker(), main(e.clicker()));
        }));
    }

    public void shutdown() {
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }

    // ==================== BUILDERS ====================

    private static final class Builder {
        final String title;
        final int rows;
        final Map<Integer, MenuItem> slots = new HashMap<>();
        boolean filled = true;

        Builder(String title, int rows) {
            this.title = title;
            this.rows = rows;
        }

        Builder put(int slot, Item icon, Consumer<MenuClick> onClick) {
            slots.put(slot, new MenuItem(icon, onClick));
            return this;
        }

        Builder label(int slot, Item icon) {
            slots.put(slot, MenuItem.label(icon));
            return this;
        }

        Menu build() {
            return new Menu(title, rows, slots, filled ? FILLER : null);
        }
    }

    private static final Item FILLER = Item.of(Ids.GRAY_STAINED_GLASS_PANE).named(" ");

    private static Item item(String id, String name, String... lore) {
        Item it = Item.of(id).named(name);
        return lore.length > 0 ? it.withLore(Arrays.asList(lore)) : it;
    }

    /** A disabled-looking entry: grey, with the reason it can't be used. */
    private static Item unavailable(String name, String reason) {
        return item(Ids.GRAY_DYE, Colors.DARK_GRAY + Colors.strip(name), Colors.GRAY + reason);
    }

    private static Item back() {
        return item(Ids.ARROW, Colors.WHITE + "Back", Colors.GRAY + "Return to the main menu");
    }

    private static String onOff(boolean b) {
        return b ? Colors.GREEN + "ON" : Colors.RED + "OFF";
    }

    // ==================== HELPERS ====================

    private ButlerService npc() {
        return core.butlers();
    }

    private Config cfg() {
        return platform.config();
    }

    /** The menu permission was declared in plugin.yml but never actually checked. */
    private boolean mayUseMenu(Owner p) {
        if (p.hasPermission("jarvis.menu.use")) return true;
        p.message(Colors.RED + "Jarvis: You aren't permitted to use my controls, sir.");
        return false;
    }

    public void openMainMenu(Owner p) {
        if (!mayUseMenu(p)) return;
        platform.ui().open(p, main(p));
    }

    private void open(Owner p, Menu menu) {
        platform.ui().open(p, menu);
        p.sound(Ids.SOUND_UI_BUTTON_CLICK, 0.5f, 1f);
    }

    private void close(Owner p) {
        platform.ui().close(p);
    }

    /** Close the menu and run a slash command, so permissions stay in one place. */
    private void run(Owner p, String... args) {
        close(p);
        core.commands().jarvis(p, Optional.of(p), Arrays.asList(args), false);
    }

    /** Nudge a numeric config value within bounds and persist it. */
    private void adjust(String path, int def, int delta, int min, int max) {
        int next = Math.max(min, Math.min(cfg().getInt(path, def) + delta, max));
        cfg().set(path, next);
        cfg().save();
    }

    private void toggle(String path, boolean def) {
        cfg().set(path, !cfg().getBoolean(path, def));
        cfg().save();
    }

    // ==================== MAIN MENU ====================

    private Menu main(Owner p) {
        Builder m = new Builder("Jarvis", 6);
        ButlerService npc = npc();
        boolean summoned = npc.exists(p);
        DepositManager deposit = npc.deposits();

        // Row 1 — presence and pockets
        if (summoned) {
            m.put(0, item(Ids.BARRIER, Colors.RED + "Dismiss",
                    Colors.GRAY + "Send Jarvis away", Colors.GRAY + "Items are kept"),
                    c -> { npc.dismiss(p); close(p); });
        } else {
            m.put(0, item(Ids.ARMOR_STAND, Colors.GREEN + "Summon",
                    Colors.GRAY + "Call Jarvis to your side"),
                    c -> { npc.summon(p); close(p); });
        }
        m.put(1, item(Ids.COMPASS, Colors.AQUA + "Come here", Colors.GRAY + "Recall him to you"),
                c -> { npc.returnToPlayer(p); close(p); });
        m.put(2, item(Ids.LEAD, Colors.GREEN + "Follow", Colors.GRAY + "Stay close and carry loot"),
                c -> { npc.follow(p); close(p); });
        m.put(3, item(Ids.REDSTONE_BLOCK, Colors.RED + "Stop", Colors.GRAY + "Halt whatever he's doing"),
                c -> { npc.stop(p); close(p); });

        m.put(5, item(Ids.CHEST, Colors.GOLD + "Loot", Colors.GRAY + "Open what he's collected"),
                c -> npc.openInventory(p));
        if (deposit.hasChest(p)) {
            m.put(6, item(Ids.HOPPER, Colors.YELLOW + "Deposit", Colors.GRAY + "Deliver loot to your chest"),
                    c -> { if (deposit.hasChest(p)) { deposit.deposit(p); close(p); } });
        } else {
            m.label(6, unavailable("Deposit", "No chest registered yet"));
        }
        m.put(7, item(Ids.ENDER_CHEST, Colors.YELLOW + "Set deposit chest",
                Colors.GRAY + "Registers the chest you're looking at"),
                c -> { deposit.setChest(p); close(p); });
        m.put(8, item(Ids.LAVA_BUCKET, Colors.RED + "Clear loot",
                Colors.GRAY + "Drop everything he carries", Colors.DARK_GRAY + "Asks first"),
                c -> open(p, confirmClear(p)));

        // Row 3 — the departments
        m.put(19, item(Ids.NETHERITE_PICKAXE, Colors.AQUA + "Mining", Colors.GRAY + "Ores, branch mines, shafts"),
                c -> open(p, mining(p)));
        m.put(20, item(Ids.NETHERITE_SWORD, Colors.RED + "Combat & Guard", Colors.GRAY + "Stances, night watch, patrol"),
                c -> open(p, combat(p)));
        m.put(21, item(Ids.WHEAT, Colors.GREEN + "Groundskeeping", Colors.GRAY + "Farm, chop, fish, light the place"),
                c -> open(p, grounds(p)));
        m.put(22, item(Ids.BRICKS, Colors.GOLD + "Building", Colors.GRAY + "The schematic library"),
                c -> open(p, building(p)));
        m.put(23, item(Ids.CLOCK, Colors.LIGHT_PURPLE + "Household", Colors.GRAY + "Home, escort, death drops"),
                c -> open(p, household(p)));
        m.put(24, item(Ids.BOOK, Colors.YELLOW + "Steward", Colors.GRAY + "Briefing, standing duties"),
                c -> open(p, steward(p)));
        ProgressionManager progression = core.progression();
        if (progression != null && progression.isEnabled()) {
            Rank rank = progression.rankOf(p);
            m.put(26, item(Ids.GOLDEN_HELMET, Colors.GOLD + "Service record",
                    Colors.GRAY + "Rank " + rank.number() + " — " + rank.title(),
                    Colors.DARK_GRAY + "What he's earned, and what's next"),
                    c -> open(p, serviceRecord(p)));
        }
        m.put(25, item(Ids.REDSTONE, Colors.LIGHT_PURPLE + "Settings", Colors.GRAY + "Torches, pickup range, returns"),
                c -> open(p, settings(p)));

        // Row 5 — status, and it is clickable when there is something to stop
        String task = npc.describeCurrentTask(p.id());
        if (!summoned) {
            m.label(40, item(Ids.GRAY_DYE, Colors.GRAY + "Not summoned", Colors.DARK_GRAY + "Summon him to begin"));
        } else if (task != null) {
            m.put(40, item(Ids.LIME_DYE, Colors.GREEN + task, Colors.GRAY + "Click to stop"),
                    c -> { if (npc.describeCurrentTask(p.id()) != null) { npc.stop(p); close(p); } });
        } else {
            m.label(40, item(Ids.PAPER, Colors.WHITE + "Awaiting orders", Colors.GRAY + "Summoned and idle"));
        }

        if (p.hasPermission("jarvis.admin")) {
            m.put(45, item(Ids.COMMAND_BLOCK, Colors.DARK_RED + "Admin", Colors.GRAY + "Requests, reload, AI health"),
                    c -> { if (p.hasPermission("jarvis.admin")) open(p, admin(p)); });
        }
        m.put(53, item(Ids.OAK_DOOR, Colors.WHITE + "Close"), c -> close(p));

        return m.build();
    }

    // ==================== MINING ====================

    private Menu mining(Owner p) {
        Builder m = new Builder("Jarvis — Mining", 4);
        ButlerService npc = npc();
        Config cfg = cfg();

        m.put(10, item(Ids.DIAMOND_ORE, Colors.AQUA + "Mine ores", Colors.GRAY + "Find and follow nearby veins"),
                c -> { npc.mine(p); close(p); });
        m.put(11, item(Ids.RAIL, Colors.GOLD + "Branch mine", Colors.GRAY + "Dig a lit branch pattern here"),
                c -> { npc.startBranchMining(p); close(p); });
        m.put(12, item(Ids.LADDER, Colors.YELLOW + "Dig a shaft", Colors.GRAY + "Straight down, safely"),
                c -> { npc.digDown(p, 0); close(p); });
        m.put(13, item(Ids.REDSTONE_BLOCK, Colors.RED + "Stop", Colors.GRAY + "Halt the current dig"),
                c -> { npc.stop(p); close(p); });

        ProgressionManager prog = core.progression();
        boolean canTunnel = prog == null || prog.has(p, Rank.Capability.WIDE_BORE);
        if (canTunnel) {
            m.put(9, item(Ids.NETHERITE_PICKAXE, Colors.GOLD + "Drive a tunnel",
                    Colors.GRAY + "A straight 3x3 passage where he faces",
                    Colors.DARK_GRAY + "Length: " + cfg.getInt("mining.tunnel.default-length", 32)),
                    c -> { npc.tunnel(p, 0); close(p); });
        } else {
            m.label(9, unavailable("Drive a tunnel", "Unlocked at " + Rank.PEERLESS.title()));
        }

        boolean torches = cfg.getBoolean("mining.place-torches", false);
        m.put(15, item(torches ? Ids.TORCH : Ids.COAL,
                Colors.YELLOW + "Torches: " + onOff(torches),
                Colors.GRAY + "Light the tunnel as he goes", Colors.DARK_GRAY + "Click to toggle"),
                c -> { toggle("mining.place-torches", false); open(p, mining(p)); });
        m.put(16, item(Ids.LANTERN,
                Colors.GOLD + "Torch spacing: " + cfg.getInt("mining.torch-spacing", 8),
                Colors.GRAY + "Blocks between torches", Colors.DARK_GRAY + "Left +1  ·  Right -1"),
                c -> { adjust("mining.torch-spacing", 8, c.left() ? 1 : -1, 3, 20); open(p, mining(p)); });

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== COMBAT & GUARD ====================

    private Menu combat(Owner p) {
        Builder m = new Builder("Jarvis — Combat & Guard", 4);
        ButlerService npc = npc();
        DepositManager deposit = npc.deposits();

        m.put(10, item(Ids.SHIELD, Colors.BLUE + "Stance: Passive", Colors.GRAY + "Stay by you, never swing first"),
                c -> { npc.guard(p, "passive"); close(p); });
        m.put(11, item(Ids.IRON_SWORD, Colors.YELLOW + "Stance: Defensive", Colors.GRAY + "Answer anything that attacks you"),
                c -> { npc.guard(p, "defensive"); close(p); });
        m.put(12, item(Ids.NETHERITE_SWORD, Colors.RED + "Stance: Aggressive", Colors.GRAY + "Hunt hostiles on sight"),
                c -> { npc.guard(p, "aggressive"); close(p); });

        m.put(14, item(Ids.CAMPFIRE, Colors.GOLD + "Night watch", Colors.GRAY + "Hold this spot and keep guard"),
                c -> { npc.watch(p, null); close(p); });

        int points = deposit.getPatrol(p).size();
        m.put(15, item(Ids.OAK_SIGN, Colors.AQUA + "Patrol: add waypoint",
                Colors.GRAY + "Marks where you're standing", Colors.DARK_GRAY + "Waypoints so far: " + points),
                c -> { npc.patrol(p, "add"); open(p, combat(p)); });
        if (points >= 2) {
            m.put(16, item(Ids.MAP, Colors.GREEN + "Patrol: start", Colors.GRAY + "Walk the " + points + "-point circuit"),
                    c -> { if (deposit.getPatrol(p).size() >= 2) { npc.patrol(p, "start"); close(p); } });
        } else {
            m.label(16, unavailable("Patrol: start", "Needs at least two waypoints"));
        }
        m.put(17, item(Ids.BARRIER, Colors.RED + "Patrol: clear", Colors.GRAY + "Forget the route"),
                c -> { npc.patrol(p, "clear"); open(p, combat(p)); });

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== GROUNDSKEEPING ====================

    private Menu grounds(Owner p) {
        Builder m = new Builder("Jarvis — Groundskeeping", 4);
        ButlerService npc = npc();
        Config cfg = cfg();

        m.put(10, item(Ids.WHEAT, Colors.GREEN + "Harvest once", Colors.GRAY + "Reap and replant the field, then stop"),
                c -> { npc.farm(p, null, false); close(p); });
        m.put(11, item(Ids.IRON_HOE, Colors.GREEN + "Tend the field", Colors.GRAY + "Stay on as a standing farmhand"),
                c -> { npc.farm(p, null, true); close(p); });

        int trees = cfg.getInt("farming.chop-count", 5);
        m.put(12, item(Ids.IRON_AXE, Colors.GOLD + "Fell " + trees + " trees",
                Colors.GRAY + "Chops and replants saplings", Colors.DARK_GRAY + "Left +1  ·  Right -1"),
                c -> {
                    if (c.right()) {
                        adjust("farming.chop-count", 5, -1, 1, 32);
                        open(p, grounds(p));
                    } else {
                        npc.chop(p, cfg.getInt("farming.chop-count", 5));
                        close(p);
                    }
                });
        m.put(13, item(Ids.FISHING_ROD, Colors.AQUA + "Go fishing", Colors.GRAY + "A spot of angling"),
                c -> { npc.fish(p); close(p); });

        int radius = cfg.getInt("lighting.default-radius", 16);
        String type = cfg.getString("lighting.default-type", "torch");
        m.put(15, item(Ids.TORCH, Colors.YELLOW + "Light the area",
                Colors.GRAY + "Spawn-proof " + radius + " blocks with " + type,
                Colors.DARK_GRAY + "Left +2 radius  ·  Right -2"),
                c -> {
                    if (c.right()) {
                        adjust("lighting.default-radius", 16, -2, 4, 48);
                        open(p, grounds(p));
                    } else {
                        npc.light(p, -1, null, -1);
                        close(p);
                    }
                });
        m.put(16, item(Ids.SOUL_LANTERN, Colors.GOLD + "Light type: " + type,
                Colors.GRAY + "torch → lantern → end_rod", Colors.DARK_GRAY + "Click to cycle"),
                c -> {
                    String cur = cfg.getString("lighting.default-type", "torch");
                    String next = switch (cur == null ? "torch" : cur.toLowerCase(Locale.ROOT)) {
                        case "torch"   -> "lantern";
                        case "lantern" -> "end_rod";
                        default        -> "torch";
                    };
                    cfg.set("lighting.default-type", next);
                    cfg.save();
                    open(p, grounds(p));
                });

        m.put(22, item(Ids.JUKEBOX, Colors.LIGHT_PURPLE + "Dance", Colors.GRAY + "The performance"),
                c -> { npc.dance(p); close(p); });

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== BUILDING ====================

    private Menu building(Owner p) {
        Builder m = new Builder("Jarvis — Building", 4);
        var sm = core.schematics();
        int count = sm == null ? 0 : sm.getSchematics().size();

        if (count > 0) {
            m.put(10, item(Ids.BOOKSHELF, Colors.GOLD + "Schematic library",
                    Colors.GRAY + count + " available", Colors.DARK_GRAY + "Click one to build it here"),
                    c -> { if (!sm.getSchematics().isEmpty()) open(p, schematicPicker(p, 0)); });
        } else {
            m.label(10, unavailable("Schematic library", "No schematics in the folder"));
        }
        m.put(11, item(Ids.SPYGLASS, Colors.AQUA + "Rescan folder", Colors.GRAY + "Pick up newly added files"),
                c -> run(p, "schematic", "scan"));
        m.put(12, item(Ids.BARRIER, Colors.RED + "Cancel build", Colors.GRAY + "Stop the build in progress"),
                c -> run(p, "cancelbuild"));
        m.put(13, item(Ids.WRITABLE_BOOK, Colors.GREEN + "Custom build",
                Colors.GRAY + "Describe it in chat and he plans it", Colors.DARK_GRAY + "Same as /jarvis build <description>"),
                c -> askInChat(p, "What shall I build, sir? Describe it in a line.", description -> {
                    String[] words = description.trim().split("\\s+");
                    if (words.length == 0 || words[0].isEmpty()) {
                        p.message(Colors.GRAY + "Nothing to build, then.");
                        return;
                    }
                    List<String> args = new ArrayList<>(words.length + 1);
                    args.add("build");
                    args.addAll(Arrays.asList(words));
                    core.commands().jarvis(p, Optional.of(p), args, false);
                }));

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    private List<String> schematicNames() {
        List<String> names = new ArrayList<>();
        var sm = core.schematics();
        if (sm != null) {
            for (var info : sm.getSchematics()) names.add(info.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private Menu schematicPicker(Owner p, int page) {
        List<String> names = schematicNames();

        int pages = Math.max(1, (int) Math.ceil(names.size() / (double) PER_PAGE));
        final int at = Math.max(0, Math.min(page, pages - 1));

        Builder m = new Builder("Schematics (" + (at + 1) + "/" + pages + ")", 6);
        m.filled = false;   // deliberately not filled — the grid reads better empty

        int from = at * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < names.size(); i++) {
            String name = names.get(from + i);
            m.put(i, item(Ids.PAPER, Colors.WHITE + name, Colors.GRAY + "Build this at your position"),
                    c -> { close(p); core.schematics().pasteSchematic(p, name); });
        }

        if (at > 0) {
            m.put(45, item(Ids.ARROW, Colors.WHITE + "Previous page"), c -> open(p, schematicPicker(p, at - 1)));
        }
        if (at < pages - 1) {
            m.put(53, item(Ids.ARROW, Colors.WHITE + "Next page"), c -> open(p, schematicPicker(p, at + 1)));
        }
        m.put(49, back(), c -> open(p, building(p)));
        return m.build();
    }

    // ==================== HOUSEHOLD ====================

    private Menu household(Owner p) {
        Builder m = new Builder("Jarvis — Household", 4);
        ButlerService npc = npc();
        DepositManager deposit = npc.deposits();

        m.put(10, item(Ids.RED_BED, Colors.LIGHT_PURPLE + "Set home", Colors.GRAY + "Remember this spot as home"),
                c -> { npc.getEscortService().setHome(p); open(p, household(p)); });
        if (deposit.getHome(p).isPresent()) {
            m.put(11, item(Ids.COMPASS, Colors.AQUA + "Escort me home", Colors.GRAY + "Jarvis walks you back"),
                    c -> { if (deposit.getHome(p).isPresent()) { npc.getEscortService().takeHome(p); close(p); } });
        } else {
            m.label(11, unavailable("Escort me home", "No home set yet"));
        }

        if (npc.getRecoveryService().hasDeathPoint(p)) {
            m.put(12, item(Ids.TOTEM_OF_UNDYING, Colors.GOLD + "Recover my drops",
                    Colors.GRAY + "Fetch what you left where you died"),
                    c -> { if (npc.getRecoveryService().hasDeathPoint(p)) { npc.getRecoveryService().recover(p); close(p); } });
        } else {
            m.label(12, unavailable("Recover my drops", "Nothing to recover"));
        }

        m.put(14, item(Ids.ENDER_CHEST, Colors.YELLOW + "Set deposit chest",
                Colors.GRAY + "Registers the chest you're looking at"),
                c -> { deposit.setChest(p); open(p, household(p)); });
        if (deposit.hasChest(p)) {
            m.put(15, item(Ids.HOPPER, Colors.YELLOW + "Deposit now", Colors.GRAY + "Deliver loot to your chest"),
                    c -> { if (deposit.hasChest(p)) { deposit.deposit(p); close(p); } });
        } else {
            m.label(15, unavailable("Deposit now", "No chest registered yet"));
        }

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== STEWARD ====================

    private Menu steward(Owner p) {
        Builder m = new Builder("Jarvis — Steward", 4);

        m.put(11, item(Ids.WRITTEN_BOOK, Colors.GOLD + "Briefing", Colors.GRAY + "The server status report"),
                c -> run(p, "report"));
        m.put(12, item(Ids.CLOCK, Colors.AQUA + "Standing duties", Colors.GRAY + "What he does on a schedule"),
                c -> run(p, "duties"));
        m.put(13, item(Ids.AMETHYST_SHARD, Colors.LIGHT_PURPLE + "AI status", Colors.GRAY + "Routing and provider health"),
                c -> run(p, "ai"));
        m.put(14, item(Ids.NAME_TAG, Colors.WHITE + "Version", Colors.GRAY + "What is actually running"),
                c -> run(p, "version"));

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== SERVICE RECORD ====================

    private Menu serviceRecord(Owner p) {
        Builder m = new Builder("Jarvis — Service Record", 6);
        ProgressionManager progression = core.progression();
        var record = progression.recordOf(p);
        Rank rank = progression.rankOf(p);
        boolean exempt = progression.isExempt(p);

        m.label(4, item(Ids.GOLDEN_HELMET,
                Colors.GOLD + "Rank " + rank.number() + " — " + rank.title(),
                exempt ? Colors.LIGHT_PURPLE + "Operator: top kit, no climb required"
                       : Colors.GRAY + "Service: " + record.service(),
                Colors.DARK_GRAY + "Current kit: " + kitLine(rank)));

        // What he has actually done
        m.label(19, tally(Ids.DIAMOND_ORE, "Ore mined", record.oresMined()));
        m.label(20, tally(Ids.OAK_LOG, "Trees felled", record.treesFelled()));
        m.label(21, tally(Ids.WHEAT, "Crops harvested", record.cropsHarvested()));
        m.label(22, tally(Ids.COD, "Fish landed", record.fishCaught()));
        m.label(23, tally(Ids.ROTTEN_FLESH, "Threats felled", record.threatsFelled()));
        m.label(24, tally(Ids.BRICKS, "Blocks laid", record.blocksPlaced()));

        // The ladder, so there is something to aim at
        Rank[] ranks = Rank.values();
        for (int i = 0; i < ranks.length && i < 9; i++) {
            Rank r = ranks[i];
            boolean earned = r.ordinal() <= rank.ordinal();
            m.label(36 + i, item(
                    earned ? Ids.LIME_DYE : Ids.GRAY_DYE,
                    (earned ? Colors.GREEN : Colors.DARK_GRAY) + r.title(),
                    Colors.GRAY + "Rank " + r.number(),
                    Colors.DARK_GRAY + (earned ? "Earned" : "At " + r.serviceRequired() + " service"),
                    Colors.WHITE + r.whatIsNew()));
        }

        Rank next = rank.next();
        if (exempt) {
            m.label(49, item(Ids.NETHERITE_INGOT, Colors.LIGHT_PURPLE + "Operator",
                    Colors.GRAY + "You are issued the top kit regardless.",
                    Colors.DARK_GRAY + "Swap his tools freely — they won't be overwritten."));
        } else if (next != null) {
            m.label(49, item(Ids.EXPERIENCE_BOTTLE,
                    Colors.AQUA + "Next: " + next.title(),
                    Colors.GRAY + record.serviceToNext() + " more service",
                    Colors.WHITE + "Brings: " + next.whatIsNew()));
        } else {
            m.label(49, item(Ids.NETHERITE_INGOT, Colors.GOLD + "Nothing left to earn",
                    Colors.GRAY + "He is as good as he gets, sir."));
        }

        m.put(45, back(), c -> open(p, main(p)));
        return m.build();
    }

    private static Item tally(String icon, String label, int count) {
        return item(icon, Colors.WHITE + label, Colors.GRAY + count);
    }

    private static String kitLine(Rank rank) {
        StringBuilder sb = new StringBuilder(rank.tier().name().toLowerCase(Locale.ROOT));
        for (var e : rank.enchants().entrySet()) {
            sb.append(", ").append(Rank.pretty(e.getKey())).append(' ').append(Rank.roman(e.getValue()));
        }
        return sb.toString();
    }

    // ==================== SETTINGS ====================

    private Menu settings(Owner p) {
        Builder m = new Builder("Jarvis — Settings", 4);
        Config cfg = cfg();

        boolean torches = cfg.getBoolean("mining.place-torches", false);
        m.put(10, item(torches ? Ids.TORCH : Ids.COAL,
                Colors.YELLOW + "Torch placement: " + onOff(torches), Colors.DARK_GRAY + "Click to toggle"),
                c -> { toggle("mining.place-torches", false); open(p, settings(p)); });

        m.put(11, item(Ids.ENDER_PEARL,
                Colors.AQUA + "Pickup range: " + cfg.getInt("mining.pickup-radius", 8),
                Colors.GRAY + "How far he'll step for a dropped item", Colors.DARK_GRAY + "Left +1  ·  Right -1"),
                c -> { adjust("mining.pickup-radius", 8, c.left() ? 1 : -1, 2, 16); open(p, settings(p)); });

        boolean autoReturn = cfg.getBoolean("mining.auto-return", true);
        m.put(12, item(autoReturn ? Ids.ENDER_EYE : Ids.ENDER_PEARL,
                Colors.LIGHT_PURPLE + "Auto-return: " + onOff(autoReturn),
                Colors.GRAY + "Come back when stuck or full", Colors.DARK_GRAY + "Click to toggle"),
                c -> { toggle("mining.auto-return", true); open(p, settings(p)); });

        m.put(14, item(Ids.LANTERN,
                Colors.GOLD + "Torch spacing: " + cfg.getInt("mining.torch-spacing", 8),
                Colors.DARK_GRAY + "Left +1  ·  Right -1"),
                c -> { adjust("mining.torch-spacing", 8, c.left() ? 1 : -1, 3, 20); open(p, settings(p)); });

        m.put(15, item(Ids.NOTE_BLOCK,
                Colors.GREEN + "Vein mining: " + onOff(cfg.getBoolean("mining.enable-vein-mining", true)),
                Colors.GRAY + "Follow a whole vein once he finds it", Colors.DARK_GRAY + "Click to toggle"),
                c -> { toggle("mining.enable-vein-mining", true); open(p, settings(p)); });

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== ADMIN ====================

    private Menu admin(Owner p) {
        Builder m = new Builder("Jarvis — Admin", 4);

        m.put(11, item(Ids.WRITABLE_BOOK, Colors.GOLD + "Pending requests", Colors.GRAY + "Item requests awaiting a decision"),
                c -> { if (p.hasPermission("jarvis.admin")) run(p, "requests"); });
        m.put(12, item(Ids.AMETHYST_SHARD, Colors.LIGHT_PURPLE + "AI setup", Colors.GRAY + "Providers, keys, models and a test"),
                c -> { if (p.hasPermission("jarvis.admin")) open(p, aiSetup(p)); });
        m.put(13, item(Ids.REPEATER, Colors.AQUA + "Reload config", Colors.GRAY + "Re-read config.yml"),
                c -> { if (p.hasPermission("jarvis.admin")) run(p, "reload"); });
        m.put(14, item(Ids.CHEST_MINECART, Colors.YELLOW + "Export dataset", Colors.GRAY + "Dump intent & build pairs as JSONL"),
                c -> { if (p.hasPermission("jarvis.admin")) run(p, "export-dataset"); });
        m.put(15, item(Ids.NOTE_BLOCK, Colors.GREEN + "Voice setup", Colors.GRAY + "Speech server, gate, and a test"),
                c -> { if (p.hasPermission("jarvis.admin")) open(p, voiceSetup(p)); });

        m.put(31, back(), c -> open(p, main(p)));
        return m.build();
    }

    // ==================== VOICE SETUP ====================

    private Menu voiceSetup(Owner p) {
        Builder m = new Builder("Jarvis — Voice", 3);
        VoiceConfig v = core.voiceConfig();
        boolean on = v.enabled();

        m.put(10, Item.of(on ? Ids.NOTE_BLOCK : Ids.GRAY_DYE)
                        .named((on ? Colors.GREEN : Colors.DARK_GRAY) + "Voice " + onOff(on))
                        .withLore(List.of(Colors.GRAY + "Needs Simple Voice Chat on the server", Colors.DARK_GRAY + "Click to turn " + (on ? "off" : "on"))),
                c -> { if (p.hasPermission("jarvis.admin")) { v.setEnabled(!v.enabled()); open(p, voiceSetup(p)); } });
        boolean embedded = v.engine().equals("embedded");
        m.put(11, item(embedded ? Ids.NOTE_BLOCK : Ids.COMPASS, Colors.AQUA + "Engine: " + v.engine(),
                        Colors.GRAY + (embedded ? "Whisper and Piper inside the server; models fetched on first use"
                                : "A speech server on your network"),
                        Colors.DARK_GRAY + "Click to switch to " + (embedded ? "server" : "embedded")),
                c -> { if (p.hasPermission("jarvis.admin")) { v.setEngine(embedded ? "server" : "embedded"); open(p, voiceSetup(p)); } });
        if (!embedded) {
            m.put(14, item(Ids.COMPASS, Colors.AQUA + "Speech server", Colors.WHITE + v.endpoint(),
                            Colors.GRAY + "Where transcription and his voice come from", Colors.DARK_GRAY + "Click to type a new address"),
                    c -> askInChat(p, "Where is the speech server? (Currently " + v.endpoint() + ".)", url -> {
                        String why = v.setEndpoint(url);
                        p.message(why != null ? Colors.RED + "Jarvis: " + why + ", sir."
                                : Colors.GREEN + "Jarvis: Speech server set to " + Colors.WHITE + v.endpoint());
                        open(p, voiceSetup(p));
                    }));
        }
        m.put(12, item(Ids.LEVER, Colors.YELLOW + "Gate: " + v.gate(),
                        Colors.GRAY + "whisper: hold the whisper key", Colors.GRAY + "always: everything you say",
                        Colors.GRAY + "wake-word: sentences with his name", Colors.DARK_GRAY + "Click to cycle"),
                c -> { if (p.hasPermission("jarvis.admin")) { v.setGate(v.nextGate()); open(p, voiceSetup(p)); } });
        m.put(13, item(v.speakReplies() ? Ids.BELL : Ids.GRAY_DYE, Colors.WHITE + "Speak replies " + onOff(v.speakReplies()),
                        Colors.GRAY + "Off keeps his replies in chat"),
                c -> { if (p.hasPermission("jarvis.admin")) { v.setSpeakReplies(!v.speakReplies()); open(p, voiceSetup(p)); } });
        m.put(16, item(Ids.BOOK, Colors.WHITE + "Status & test", Colors.GRAY + "Every link of the chain, in chat"),
                c -> run(p, "voice"));

        m.put(22, backTo("the admin page"), c -> open(p, admin(p)));
        return m.build();
    }

    // ==================== AI SETUP ====================

    private static String providerTitle(String provider) {
        return switch (provider) {
            case "ollama" -> "Ollama";
            case "claude" -> "Claude";
            case "openai" -> "OpenAI";
            case "grok" -> "Grok";
            case "gemini" -> "Gemini";
            default -> provider;
        };
    }

    private static String providerIcon(String provider) {
        return switch (provider) {
            case "ollama" -> Ids.LANTERN;
            case "claude" -> Ids.AMETHYST_SHARD;
            case "openai" -> Ids.ENDER_EYE;
            case "grok" -> Ids.END_ROD;
            case "gemini" -> Ids.NAUTILUS_SHELL;
            default -> Ids.PAPER;
        };
    }

    private static String statusColour(String status) {
        if (status.startsWith("available")) return Colors.GREEN;
        if (status.startsWith("cooldown")) return Colors.RED;
        if (status.startsWith("no API key")) return Colors.YELLOW;
        return Colors.GRAY;
    }

    private static Item backTo(String where) {
        return item(Ids.ARROW, Colors.WHITE + "Back", Colors.GRAY + "Return to " + where);
    }

    /** One row of providers: left to set one up, right to switch it on or off. */
    private Menu aiSetup(Owner p) {
        Builder m = new Builder("Jarvis — AI providers", 3);
        AiSettings s = core.aiSettings();
        int slot = 10;
        for (String provider : AiSettings.PROVIDERS) {
            boolean on = s.isEnabled(provider);
            String status = s.status(provider);
            List<String> lore = new ArrayList<>();
            lore.add(Colors.GRAY + "Status: " + statusColour(status) + status);
            lore.add(Colors.GRAY + "Model: " + Colors.WHITE + s.model(provider));
            if (s.needsKey(provider)) {
                lore.add(Colors.GRAY + "Key: " + (s.hasKey(provider) ? Colors.GREEN + "set" : Colors.RED + "not set"));
            } else {
                lore.add(Colors.GRAY + "Server: " + Colors.WHITE + s.endpoint(provider));
            }
            lore.add(Colors.DARK_GRAY + "Left: set up  ·  Right: " + (on ? "disable" : "enable"));
            Item icon = Item.of(on ? providerIcon(provider) : Ids.GRAY_DYE)
                    .named((on ? Colors.AQUA : Colors.DARK_GRAY) + providerTitle(provider) + " " + onOff(on))
                    .withLore(lore);
            m.put(slot++, icon, c -> {
                if (!p.hasPermission("jarvis.admin")) return;
                if (c.right()) {
                    s.setEnabled(provider, !s.isEnabled(provider));
                    open(p, aiSetup(p));
                } else {
                    open(p, aiProvider(p, provider));
                }
            });
        }
        m.put(16, item(Ids.BOOK, Colors.WHITE + "Routing status", Colors.GRAY + "Light and heavy routes, in chat"),
                c -> run(p, "ai"));
        m.put(22, backTo("the admin page"), c -> open(p, admin(p)));
        return m.build();
    }

    /** One provider: on or off, its key or address, its model, and a test. */
    private Menu aiProvider(Owner p, String provider) {
        Builder m = new Builder("Jarvis — " + providerTitle(provider), 3);
        AiSettings s = core.aiSettings();
        boolean on = s.isEnabled(provider);
        String status = s.status(provider);
        m.label(4, item(providerIcon(provider), Colors.AQUA + providerTitle(provider),
                Colors.GRAY + "Status: " + statusColour(status) + status));

        m.put(10, item(on ? Ids.LIME_DYE : Ids.GRAY_DYE, Colors.WHITE + "Enabled: " + onOff(on),
                Colors.GRAY + (on ? "In the list of providers he may use" : "Skipped until switched on"),
                Colors.DARK_GRAY + "Click to toggle"),
                c -> { s.setEnabled(provider, !s.isEnabled(provider)); open(p, aiProvider(p, provider)); });

        if (s.needsKey(provider)) {
            m.put(11, item(Ids.NAME_TAG, Colors.YELLOW + "API key: " + (s.hasKey(provider) ? Colors.GREEN + "set" : Colors.RED + "not set"),
                    Colors.GRAY + "Click, then paste the key in chat.", Colors.GRAY + "Nobody else sees it; it is not logged."),
                    c -> askInChat(p, "Paste the " + providerTitle(provider) + " API key.", key -> {
                        s.setKey(provider, key);
                        p.message(Colors.GREEN + "Jarvis: Key for " + providerTitle(provider) + " stored, sir.");
                        open(p, aiProvider(p, provider));
                    }));
            m.put(12, item(Ids.BOOK, Colors.AQUA + "Model: " + Colors.WHITE + s.model(provider),
                    Colors.GRAY + "Click, then type the model name in chat."),
                    c -> askInChat(p, "Which " + providerTitle(provider) + " model? (Currently " + s.model(provider) + ".)", model -> {
                        s.setModel(provider, model);
                        p.message(Colors.GREEN + "Jarvis: " + providerTitle(provider) + " will use " + model + ", sir.");
                        open(p, aiProvider(p, provider));
                    }));
        } else {
            m.put(11, item(Ids.COMPASS, Colors.YELLOW + "Server: " + Colors.WHITE + s.endpoint(provider),
                    Colors.GRAY + "Click, then type the address in chat.", Colors.DARK_GRAY + "For example http://10.0.0.5:11434"),
                    c -> askInChat(p, "Where is the Ollama server? (Currently " + s.endpoint(provider) + ".)", url -> {
                        if (!s.setEndpoint(provider, url)) {
                            p.message(Colors.RED + "Jarvis: An address starts with http:// or https://, sir. Left as it was.");
                        } else {
                            p.message(Colors.GREEN + "Jarvis: I shall ask " + s.endpoint(provider) + ", sir.");
                        }
                        open(p, aiProvider(p, provider));
                    }));
            m.put(12, item(Ids.BOOK, Colors.AQUA + "Model: " + Colors.WHITE + s.model(provider),
                    Colors.GRAY + "Click to pick from what the server has pulled."),
                    c -> {
                        p.message(Colors.GRAY + "Jarvis: Asking " + s.endpoint(provider) + " what it offers...");
                        s.ollamaModels(models -> {
                            if (models.isEmpty()) {
                                p.message(Colors.YELLOW + "Jarvis: The server has no models pulled yet, sir. "
                                        + "Try 'ollama pull mistral' on it.");
                                open(p, aiProvider(p, provider));
                            } else {
                                open(p, ollamaModelPicker(p, models));
                            }
                        }, why -> {
                            p.message(Colors.RED + "Jarvis: I could not reach it, sir: " + why);
                            open(p, aiProvider(p, provider));
                        });
                    });
        }

        m.put(14, item(Ids.REDSTONE, Colors.GREEN + "Test connection",
                Colors.GRAY + "One small request; the verdict comes in chat."),
                c -> {
                    p.message(Colors.GRAY + "Jarvis: Trying " + providerTitle(provider) + " (" + s.model(provider) + ")...");
                    s.test(provider, verdict -> p.message(
                            (verdict.startsWith("failed") ? Colors.RED : Colors.GREEN) + "Jarvis: " + providerTitle(provider) + " " + verdict));
                });

        m.put(22, backTo("the providers"), c -> open(p, aiSetup(p)));
        return m.build();
    }

    /** The models an Ollama server offers, one per slot. */
    private Menu ollamaModelPicker(Owner p, List<String> models) {
        int shown = Math.min(models.size(), 45);
        int rows = Math.min(6, (shown + 8) / 9 + 1);
        Builder m = new Builder("Jarvis — Ollama models", rows);
        AiSettings s = core.aiSettings();
        String current = s.model("ollama");
        for (int i = 0; i < shown; i++) {
            String model = models.get(i);
            boolean chosen = model.equals(current);
            m.put(i, item(chosen ? Ids.LIME_DYE : Ids.PAPER, (chosen ? Colors.GREEN : Colors.WHITE) + model,
                    Colors.GRAY + (chosen ? "In use" : "Click to use this one")),
                    c -> {
                        s.setModel("ollama", model);
                        p.message(Colors.GREEN + "Jarvis: Ollama will use " + model + ", sir.");
                        open(p, aiProvider(p, "ollama"));
                    });
        }
        m.put(rows * 9 - 5, backTo("Ollama"), c -> open(p, aiProvider(p, "ollama")));
        return m.build();
    }

    /** Close the menu and ask for a typed value; the answer comes back on the server thread. */
    private void askInChat(Owner p, String question, java.util.function.Consumer<String> answer) {
        close(p);
        core.prompts().ask(p, question, answer);
    }

    // ==================== CONFIRM ====================

    private Menu confirmClear(Owner p) {
        Builder m = new Builder("Drop everything he carries?", 3);
        m.label(4, item(Ids.PAPER, Colors.YELLOW + "This drops ALL collected items",
                Colors.GRAY + "at Jarvis's current position.", Colors.RED + "It cannot be undone."));
        m.put(11, item(Ids.GREEN_WOOL, Colors.GREEN + "Confirm"), c -> {
            npc().clearInventory(p);
            close(p);
            p.sound(Ids.SOUND_ENTITY_ITEM_PICKUP, 1f, 0.5f);
        });
        m.put(15, item(Ids.RED_WOOL, Colors.RED + "Cancel"), c -> open(p, main(p)));
        return m.build();
    }
}
