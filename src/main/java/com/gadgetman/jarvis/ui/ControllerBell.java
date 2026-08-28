package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.Jarvis;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

public class ControllerBell {

    private final Jarvis plugin;
    private final NamespacedKey key;

    public ControllerBell(Jarvis plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "jarvis_controller");
    }

    public ItemStack create() {
        ItemStack bell = new ItemStack(Material.BELL);
        ItemMeta meta = bell.getItemMeta();
        meta.setDisplayName("§b§lJarvis Controller");
        meta.setLore(Arrays.asList(
                "§7Right-click to open Jarvis menu",
                "§7Summons, commands, and loot access",
                "§cDo not lose!"
        ));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte)1);
        bell.setItemMeta(meta);
        return bell;
    }

    public boolean isController(ItemStack item) {
        if (item == null || item.getType() != Material.BELL) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public NamespacedKey getKey() { return key; }
}
