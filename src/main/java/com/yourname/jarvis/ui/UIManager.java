package com.yourname.jarvis.ui;

import com.yourname.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Sound;

import java.util.List;

public class UIManager implements Listener {

    private final Jarvis plugin;
    private static final String MENU_TITLE = "Jarvis Controls";

    public UIManager(Jarvis plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private Inventory createMenu() {
        Inventory menu = org.bukkit.Bukkit.createInventory(null, 9, MENU_TITLE);

        menu.setItem(0, item(Material.ARMOR_STAND, "Summon Jarvis", "Spawn Jarvis near you"));
        menu.setItem(1, item(Material.BARRIER, "Dismiss Jarvis", "Remove Jarvis"));
        menu.setItem(2, item(Material.COMPASS, "Return", "Bring Jarvis back to you"));
        menu.setItem(3, item(Material.NETHERITE_SWORD, "Attack", "Defend you and hunt hostile mobs"));
        menu.setItem(4, item(Material.NETHERITE_PICKAXE, "Mine", "Find and mine valuable ores"));
        menu.setItem(5, item(Material.CHEST, "Loot", "Open Jarvis' collected items"));

        return menu;
    }

    private ItemStack item(Material mat, String name, String lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(name);
        m.setLore(List.of(lore));
        i.setItemMeta(m);
        return i;
    }

    private boolean isControllerBell(ItemStack item) {
        if (item == null || item.getType() != Material.BELL) return false;
        if (!item.hasItemMeta()) return false;
        var container = item.getItemMeta().getPersistentDataContainer();
        // Accept both current and legacy keys so older controller bells still work
        if (container.has(plugin.getControllerKey(), PersistentDataType.BYTE)) return true;
        NamespacedKey legacy = new NamespacedKey(plugin, "jarvis_controller");
        return container.has(legacy, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onBellUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        // Held bell right-click (air or block)
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) && isControllerBell(item) && e.getHand() == EquipmentSlot.HAND) {
            p.openInventory(createMenu());
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            e.setCancelled(true);
            return;
        }

        // Placed bell right-click
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.BELL) {
            Block b = e.getClickedBlock();
            if (!(b.getState() instanceof TileState ts)) return;
            if (ts.getPersistentDataContainer().has(plugin.getControllerKey(), PersistentDataType.BYTE)) {
                p.openInventory(createMenu());
                p.playSound(b.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onRightClickNPC(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof org.bukkit.entity.Player clicked) {
            Player p = e.getPlayer();
            NPC npc = plugin.getJarvisNPC().getNPCForPlayer(p.getUniqueId());
            if (npc != null && clicked.getUniqueId().equals(npc.getUniqueId())) {
                p.openInventory(createMenu());
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !e.getView().getTitle().equals(MENU_TITLE)) return;
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 6) return;

        var npc = plugin.getJarvisNPC();
        switch (slot) {
            case 0 -> npc.summon(p);
            case 1 -> npc.dismiss(p);
            case 2 -> npc.returnToPlayer(p);
            case 3 -> npc.attack(p);
            case 4 -> npc.mine(p);
            case 5 -> npc.openInventory(p);
        }
        p.closeInventory();
    }
}
