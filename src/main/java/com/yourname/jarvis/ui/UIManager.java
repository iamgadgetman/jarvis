package com.yourname.jarvis.ui;

import com.yourname.jarvis.Jarvis;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
import org.bukkit.Sound;

import java.util.Arrays;
import java.util.List;

/**
 * UIManager - Expanded GUI with submenus
 *
 * Main Menu (27 slots, 3 rows):
 * - Row 1: Summon, Dismiss, Return, Loot, Clear Loot
 * - Row 2: Mining, Combat, Settings
 * - Row 3: Status display
 *
 * Submenus: Mining, Combat, Settings, Confirm Clear
 */
public class UIManager implements Listener {

    private final Jarvis plugin;

    // Menu titles - used for identification
    private static final String MAIN_MENU_TITLE = "Jarvis Controls";
    private static final String MINING_MENU_TITLE = "Jarvis - Mining";
    private static final String COMBAT_MENU_TITLE = "Jarvis - Combat";
    private static final String SETTINGS_MENU_TITLE = "Jarvis - Settings";
    private static final String CONFIRM_CLEAR_TITLE = "Confirm Clear Loot?";

    public UIManager(Jarvis plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ==================== MAIN MENU ====================

    private Inventory createMainMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, MAIN_MENU_TITLE);

        // Row 1: Core actions
        menu.setItem(0, item(Material.ARMOR_STAND, ChatColor.GREEN + "Summon Jarvis",
            "Spawn Jarvis near you"));
        menu.setItem(1, item(Material.BARRIER, ChatColor.RED + "Dismiss Jarvis",
            "Send Jarvis away", "Items will be saved"));
        menu.setItem(2, item(Material.COMPASS, ChatColor.AQUA + "Return",
            "Teleport Jarvis to you"));
        menu.setItem(3, item(Material.CHEST, ChatColor.GOLD + "Open Loot",
            "View collected items"));
        menu.setItem(4, item(Material.HOPPER, ChatColor.YELLOW + "Clear Loot",
            "Drop all collected items", ChatColor.GRAY + "Opens confirmation"));

        // Row 2: Submenus
        menu.setItem(9, item(Material.NETHERITE_PICKAXE, ChatColor.AQUA + "Mining Menu",
            "Mining operations", "Ore mining, branch mining", "Torch settings"));
        menu.setItem(10, item(Material.NETHERITE_SWORD, ChatColor.RED + "Combat Menu",
            "Combat operations", "Attack mode, follow mode"));
        menu.setItem(11, item(Material.REDSTONE, ChatColor.LIGHT_PURPLE + "Settings",
            "Configure Jarvis", "Torch placement, pickup range"));

        // Row 3: Status display
        NPC npc = plugin.getJarvisNPC().getNPCForPlayer(player.getUniqueId());
        if (npc != null && npc.isSpawned()) {
            menu.setItem(17, item(Material.PAPER, ChatColor.GREEN + "Status: Active",
                ChatColor.WHITE + "Jarvis is summoned",
                ChatColor.GRAY + "Tasks: " + plugin.getJarvisNPC().getActiveTaskCount()));
        } else {
            menu.setItem(17, item(Material.GRAY_DYE, ChatColor.GRAY + "Status: Inactive",
                "Jarvis is not summoned",
                "Use Summon to call Jarvis"));
        }

        return menu;
    }

    // ==================== MINING SUBMENU ====================

    private Inventory createMiningMenu() {
        Inventory menu = Bukkit.createInventory(null, 27, MINING_MENU_TITLE);

        // Mining actions
        menu.setItem(10, item(Material.DIAMOND_ORE, ChatColor.AQUA + "Start Mining",
            "Find and mine valuable ores",
            "Uses smart clustering"));
        menu.setItem(11, item(Material.RAIL, ChatColor.GOLD + "Branch Mining",
            "Start branch mining pattern",
            "Efficient tunnel system"));
        menu.setItem(12, item(Material.REDSTONE_BLOCK, ChatColor.RED + "Stop Mining",
            "Stop current mining task"));

        // Mining options
        menu.setItem(14, item(Material.TORCH, ChatColor.YELLOW + "Toggle Torches",
            "Turn torch placement on/off",
            getCurrentTorchStatus()));
        menu.setItem(15, item(Material.LADDER, ChatColor.GREEN + "Set Y-Level",
            "Configure mining depth",
            ChatColor.GRAY + "Current: " + plugin.getConfig().getInt("mining.safety.min-y-level", -60)));

        // Back button
        menu.setItem(22, item(Material.ARROW, ChatColor.WHITE + "Back",
            "Return to main menu"));

        return menu;
    }

    // ==================== COMBAT SUBMENU ====================

    private Inventory createCombatMenu() {
        Inventory menu = Bukkit.createInventory(null, 27, COMBAT_MENU_TITLE);

        // Combat actions
        menu.setItem(10, item(Material.IRON_SWORD, ChatColor.RED + "Attack Mode",
            "Hunt hostile mobs",
            "Jarvis will seek enemies"));
        menu.setItem(11, item(Material.SHIELD, ChatColor.BLUE + "Passive Mode",
            "Stop attacking",
            "Jarvis will stay idle"));
        menu.setItem(12, item(Material.LEAD, ChatColor.GREEN + "Follow Player",
            "Follow you around",
            "Jarvis will stay close"));
        menu.setItem(13, item(Material.BARRIER, ChatColor.GRAY + "Stop",
            "Stop current task"));

        // Back button
        menu.setItem(22, item(Material.ARROW, ChatColor.WHITE + "Back",
            "Return to main menu"));

        return menu;
    }

    // ==================== SETTINGS SUBMENU ====================

    private Inventory createSettingsMenu() {
        Inventory menu = Bukkit.createInventory(null, 27, SETTINGS_MENU_TITLE);

        // Settings options
        boolean torchesEnabled = plugin.getConfig().getBoolean("mining.place-torches", false);
        menu.setItem(10, item(torchesEnabled ? Material.TORCH : Material.COAL,
            ChatColor.YELLOW + "Torch Placement: " + (torchesEnabled ? "ON" : "OFF"),
            "Toggle automatic torch placement",
            ChatColor.GRAY + "Click to " + (torchesEnabled ? "disable" : "enable")));

        int pickupRadius = plugin.getConfig().getInt("mining.pickup-radius", 8);
        menu.setItem(11, item(Material.ENDER_PEARL, ChatColor.AQUA + "Pickup Range: " + pickupRadius,
            "Adjust item pickup distance",
            ChatColor.GRAY + "Left-click: +1, Right-click: -1"));

        boolean autoReturn = plugin.getConfig().getBoolean("mining.auto-return", true);
        menu.setItem(12, item(autoReturn ? Material.ENDER_EYE : Material.ENDER_PEARL,
            ChatColor.LIGHT_PURPLE + "Auto-Return: " + (autoReturn ? "ON" : "OFF"),
            "Return to player when stuck",
            ChatColor.GRAY + "Click to toggle"));

        int torchSpacing = plugin.getConfig().getInt("mining.torch-spacing", 8);
        menu.setItem(14, item(Material.LANTERN, ChatColor.GOLD + "Torch Spacing: " + torchSpacing,
            "Blocks between torches",
            ChatColor.GRAY + "Left-click: +1, Right-click: -1"));

        // Back button
        menu.setItem(22, item(Material.ARROW, ChatColor.WHITE + "Back",
            "Return to main menu"));

        return menu;
    }

    // ==================== CONFIRM CLEAR SUBMENU ====================

    private Inventory createConfirmClearMenu() {
        Inventory menu = Bukkit.createInventory(null, 27, CONFIRM_CLEAR_TITLE);

        // Warning message
        menu.setItem(4, item(Material.PAPER, ChatColor.YELLOW + "Warning!",
            "This will drop ALL collected items",
            "at Jarvis's current location.",
            ChatColor.RED + "This cannot be undone!"));

        // Confirm/Cancel buttons
        menu.setItem(11, item(Material.GREEN_WOOL, ChatColor.GREEN + "Confirm",
            "Drop all items"));
        menu.setItem(15, item(Material.RED_WOOL, ChatColor.RED + "Cancel",
            "Return to main menu"));

        return menu;
    }

    // ==================== ITEM HELPERS ====================

    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getCurrentTorchStatus() {
        boolean enabled = plugin.getConfig().getBoolean("mining.place-torches", false);
        return ChatColor.GRAY + "Currently: " + (enabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF");
    }

    private boolean isControllerBell(ItemStack item) {
        if (item == null || item.getType() != Material.BELL) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
            plugin.getControllerKey(), PersistentDataType.BYTE);
    }

    // ==================== EVENT HANDLERS ====================

    @EventHandler
    public void onBellUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        // Held bell right-click (air or block)
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) &&
            isControllerBell(item) && e.getHand() == EquipmentSlot.HAND) {
            p.openInventory(createMainMenu(p));
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            e.setCancelled(true);
            return;
        }

        // Placed bell right-click
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null &&
            e.getClickedBlock().getType() == Material.BELL) {
            Block b = e.getClickedBlock();
            if (!(b.getState() instanceof TileState ts)) return;
            if (ts.getPersistentDataContainer().has(plugin.getControllerKey(), PersistentDataType.BYTE)) {
                p.openInventory(createMainMenu(p));
                p.playSound(b.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBellPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (!isControllerBell(item)) return;

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
            p.openInventory(createMainMenu(p));
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String title = e.getView().getTitle();
        int slot = e.getRawSlot();

        // Handle different menus
        switch (title) {
            case MAIN_MENU_TITLE -> handleMainMenuClick(p, slot, e);
            case MINING_MENU_TITLE -> handleMiningMenuClick(p, slot, e);
            case COMBAT_MENU_TITLE -> handleCombatMenuClick(p, slot, e);
            case SETTINGS_MENU_TITLE -> handleSettingsMenuClick(p, slot, e);
            case CONFIRM_CLEAR_TITLE -> handleConfirmClearClick(p, slot, e);
            default -> { return; } // Not our menu
        }
    }

    // ==================== CLICK HANDLERS ====================

    private void handleMainMenuClick(Player p, int slot, InventoryClickEvent e) {
        e.setCancelled(true);
        var npc = plugin.getJarvisNPC();

        switch (slot) {
            case 0 -> { // Summon
                npc.summon(p);
                p.closeInventory();
            }
            case 1 -> { // Dismiss
                npc.dismiss(p);
                p.closeInventory();
            }
            case 2 -> { // Return
                npc.returnToPlayer(p);
                p.closeInventory();
            }
            case 3 -> { // Open Loot
                npc.openInventory(p);
            }
            case 4 -> { // Clear Loot (opens confirm)
                p.openInventory(createConfirmClearMenu());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            }
            case 9 -> { // Mining Menu
                p.openInventory(createMiningMenu());
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
            case 10 -> { // Combat Menu
                p.openInventory(createCombatMenu());
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
            case 11 -> { // Settings Menu
                p.openInventory(createSettingsMenu());
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
        }
    }

    private void handleMiningMenuClick(Player p, int slot, InventoryClickEvent e) {
        e.setCancelled(true);
        var npc = plugin.getJarvisNPC();

        switch (slot) {
            case 10 -> { // Start Mining
                npc.mine(p);
                p.closeInventory();
            }
            case 11 -> { // Branch Mining
                npc.startBranchMining(p);
                p.closeInventory();
            }
            case 12 -> { // Stop Mining
                npc.stop(p);
                p.closeInventory();
            }
            case 14 -> { // Toggle Torches
                boolean current = plugin.getConfig().getBoolean("mining.place-torches", false);
                plugin.getConfig().set("mining.place-torches", !current);
                plugin.saveConfig();
                p.sendMessage(ChatColor.YELLOW + "Torch placement: " + (!current ? "enabled" : "disabled"));
                p.openInventory(createMiningMenu()); // Refresh
            }
            case 22 -> { // Back
                p.openInventory(createMainMenu(p));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
        }
    }

    private void handleCombatMenuClick(Player p, int slot, InventoryClickEvent e) {
        e.setCancelled(true);
        var npc = plugin.getJarvisNPC();

        switch (slot) {
            case 10 -> { // Attack Mode
                npc.attack(p);
                p.closeInventory();
            }
            case 11 -> { // Passive Mode
                npc.stop(p);
                p.sendMessage(ChatColor.BLUE + "Jarvis: Entering passive mode.");
                p.closeInventory();
            }
            case 12 -> { // Follow Player
                npc.returnToPlayer(p);
                p.sendMessage(ChatColor.GREEN + "Jarvis: Following you!");
                p.closeInventory();
            }
            case 13 -> { // Stop
                npc.stop(p);
                p.closeInventory();
            }
            case 22 -> { // Back
                p.openInventory(createMainMenu(p));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
        }
    }

    private void handleSettingsMenuClick(Player p, int slot, InventoryClickEvent e) {
        e.setCancelled(true);

        switch (slot) {
            case 10 -> { // Toggle Torches
                boolean current = plugin.getConfig().getBoolean("mining.place-torches", false);
                plugin.getConfig().set("mining.place-torches", !current);
                plugin.saveConfig();
                p.sendMessage(ChatColor.YELLOW + "Torch placement: " + (!current ? "enabled" : "disabled"));
                p.openInventory(createSettingsMenu()); // Refresh
            }
            case 11 -> { // Pickup Range
                int current = plugin.getConfig().getInt("mining.pickup-radius", 8);
                if (e.isLeftClick()) {
                    plugin.getConfig().set("mining.pickup-radius", Math.min(current + 1, 16));
                } else if (e.isRightClick()) {
                    plugin.getConfig().set("mining.pickup-radius", Math.max(current - 1, 2));
                }
                plugin.saveConfig();
                p.sendMessage(ChatColor.AQUA + "Pickup range: " + plugin.getConfig().getInt("mining.pickup-radius"));
                p.openInventory(createSettingsMenu()); // Refresh
            }
            case 12 -> { // Auto-Return Toggle
                boolean current = plugin.getConfig().getBoolean("mining.auto-return", true);
                plugin.getConfig().set("mining.auto-return", !current);
                plugin.saveConfig();
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Auto-return: " + (!current ? "enabled" : "disabled"));
                p.openInventory(createSettingsMenu()); // Refresh
            }
            case 14 -> { // Torch Spacing
                int current = plugin.getConfig().getInt("mining.torch-spacing", 8);
                if (e.isLeftClick()) {
                    plugin.getConfig().set("mining.torch-spacing", Math.min(current + 1, 20));
                } else if (e.isRightClick()) {
                    plugin.getConfig().set("mining.torch-spacing", Math.max(current - 1, 3));
                }
                plugin.saveConfig();
                p.sendMessage(ChatColor.GOLD + "Torch spacing: " + plugin.getConfig().getInt("mining.torch-spacing"));
                p.openInventory(createSettingsMenu()); // Refresh
            }
            case 22 -> { // Back
                p.openInventory(createMainMenu(p));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
        }
    }

    private void handleConfirmClearClick(Player p, int slot, InventoryClickEvent e) {
        e.setCancelled(true);

        switch (slot) {
            case 11 -> { // Confirm
                plugin.getJarvisNPC().clearInventory(p);
                p.closeInventory();
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.5f);
            }
            case 15 -> { // Cancel
                p.openInventory(createMainMenu(p));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            }
        }
    }
}
