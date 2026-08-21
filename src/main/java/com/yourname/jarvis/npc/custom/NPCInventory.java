package com.yourname.jarvis.npc.custom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.yourname.jarvis.Jarvis;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Simple inventory management for custom NPCs.
 * Uses Bukkit inventory GUI for player interaction.
 */
public class NPCInventory implements Listener {

    private static final int INVENTORY_SIZE = 27; // 3 rows

    private final Jarvis plugin;
    private final String title;
    private final ItemStack[] contents;

    // Track open inventories to handle close events
    private final Map<UUID, Inventory> openInventories = new HashMap<>();

    private boolean registered = false;

    public NPCInventory(Jarvis plugin, String npcName) {
        this.plugin = plugin;
        this.title = npcName + "'s Inventory";
        this.contents = new ItemStack[INVENTORY_SIZE];
    }

    /**
     * Add an item to the inventory.
     * @return true if item was added (or partially added), false if full
     */
    public boolean addItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return true;

        ItemStack toAdd = item.clone();

        // First pass: try to stack with existing items
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].isSimilar(toAdd)) {
                int canAdd = contents[i].getMaxStackSize() - contents[i].getAmount();
                if (canAdd > 0) {
                    int adding = Math.min(canAdd, toAdd.getAmount());
                    contents[i].setAmount(contents[i].getAmount() + adding);
                    toAdd.setAmount(toAdd.getAmount() - adding);

                    if (toAdd.getAmount() <= 0) {
                        return true;
                    }
                }
            }
        }

        // Second pass: find empty slots
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType() == Material.AIR) {
                contents[i] = toAdd.clone();
                return true;
            }
        }

        return false; // Inventory full
    }

    /**
     * Remove an item from the inventory.
     * @return true if removed
     */
    public boolean removeItem(ItemStack item) {
        if (item == null) return true;

        int remaining = item.getAmount();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (contents[i] != null && contents[i].isSimilar(item)) {
                int removing = Math.min(contents[i].getAmount(), remaining);
                contents[i].setAmount(contents[i].getAmount() - removing);
                remaining -= removing;

                if (contents[i].getAmount() <= 0) {
                    contents[i] = null;
                }
            }
        }

        return remaining == 0;
    }

    /**
     * Get all contents.
     */
    public ItemStack[] getContents() {
        ItemStack[] copy = new ItemStack[INVENTORY_SIZE];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] != null ? contents[i].clone() : null;
        }
        return copy;
    }

    /**
     * Set all contents.
     */
    public void setContents(ItemStack[] newContents) {
        for (int i = 0; i < contents.length; i++) {
            if (i < newContents.length && newContents[i] != null) {
                contents[i] = newContents[i].clone();
            } else {
                contents[i] = null;
            }
        }
    }

    /**
     * Open the inventory GUI for a player.
     */
    public void openFor(Player player) {
        // Register listener if not already
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }

        // Create inventory GUI
        Inventory gui = Bukkit.createInventory(null, INVENTORY_SIZE, title);

        // Copy contents to GUI
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                gui.setItem(i, contents[i].clone());
            }
        }

        openInventories.put(player.getUniqueId(), gui);
        player.openInventory(gui);
    }

    /**
     * Handle inventory close - sync contents back.
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        Inventory gui = openInventories.remove(player.getUniqueId());
        if (gui == null || !event.getInventory().equals(gui)) return;

        // Sync contents back from GUI
        for (int i = 0; i < contents.length; i++) {
            ItemStack guiItem = gui.getItem(i);
            contents[i] = guiItem != null ? guiItem.clone() : null;
        }
    }

    /**
     * Handle inventory click - allow normal interaction.
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory gui = openInventories.get(player.getUniqueId());
        if (gui == null || !event.getInventory().equals(gui)) return;

        // Allow normal inventory interaction (taking/placing items)
        // We'll sync everything when inventory closes
    }

    /**
     * Clear all contents.
     */
    public void clear() {
        for (int i = 0; i < contents.length; i++) {
            contents[i] = null;
        }
    }

    /**
     * Check if inventory is empty.
     */
    public boolean isEmpty() {
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count total items in inventory.
     */
    public int countItems() {
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Check if inventory contains a specific material.
     */
    public boolean contains(Material material) {
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get first item matching material.
     */
    public ItemStack getFirst(Material material) {
        for (ItemStack item : contents) {
            if (item != null && item.getType() == material) {
                return item.clone();
            }
        }
        return null;
    }

    /**
     * Clean up when NPC is removed.
     */
    public void cleanup() {
        // Close all open inventories
        for (UUID uuid : openInventories.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        }
        openInventories.clear();

        // Unregister listener
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }
}
