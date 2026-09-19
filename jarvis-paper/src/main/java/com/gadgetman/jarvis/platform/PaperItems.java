package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves core's namespaced ids to Bukkit's registry constants, and converts item stacks. */
public final class PaperItems {

    private PaperItems() { }

    /** Where an item's core marker is kept on Paper. */
    public static final NamespacedKey MARKER = new NamespacedKey("jarvis", "marker");

    /** The mark the controller bell carried before markers existed; bells already in pockets still have it. */
    private static final NamespacedKey LEGACY_CONTROLLER = new NamespacedKey("jarvis", "jarvis-controller");

    /** The material for an id such as {@code minecraft:diamond_sword}. */
    public static Material material(String id) {
        Material m = Material.matchMaterial(id);
        if (m == null) throw new IllegalArgumentException("Unknown material id: " + id);
        return m;
    }

    public static String id(Material material) {
        return material.getKey().toString();
    }

    /** The enchantment for an id such as {@code minecraft:efficiency}, or null. */
    @SuppressWarnings("deprecation")
    public static Enchantment enchantment(String id) {
        NamespacedKey key = NamespacedKey.fromString(Ids.of(id));
        return key == null ? null : Enchantment.getByKey(key);
    }

    public static String id(Enchantment enchantment) {
        return enchantment.getKey().toString();
    }

    /** Core's view of a stack. Null and air both read as {@link Item#EMPTY}. */
    @SuppressWarnings("deprecation")
    public static Item toItem(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) return Item.EMPTY;
        Map<String, Integer> enchants = new HashMap<>();
        stack.getEnchantments().forEach((e, level) -> enchants.put(id(e), level));
        String name = null;
        List<String> lore = List.of();
        String marker = null;
        boolean unbreakable = false;
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta.hasDisplayName()) name = meta.getDisplayName();
            if (meta.hasLore()) lore = meta.getLore();
            unbreakable = meta.isUnbreakable();
            marker = meta.getPersistentDataContainer().get(MARKER, PersistentDataType.STRING);
            if (marker == null && meta.getPersistentDataContainer().has(LEGACY_CONTROLLER, PersistentDataType.BYTE)) {
                marker = com.gadgetman.jarvis.ui.ControllerBell.MARKER;
            }
        }
        return new Item(id(stack.getType()), stack.getAmount(), enchants, name, lore, marker, unbreakable);
    }

    @SuppressWarnings("deprecation")
    public static ItemStack toStack(Item item) {
        if (item == null || item.isEmpty()) return new ItemStack(Material.AIR);
        ItemStack stack = new ItemStack(material(item.id()), item.count());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (item.displayName() != null) meta.setDisplayName(item.displayName());
            if (!item.lore().isEmpty()) meta.setLore(new ArrayList<>(item.lore()));
            if (item.unbreakable()) meta.setUnbreakable(true);
            if (item.marker() != null) {
                meta.getPersistentDataContainer().set(MARKER, PersistentDataType.STRING, item.marker());
            }
            item.enchants().forEach((id, level) -> {
                Enchantment e = enchantment(id);
                if (e != null) meta.addEnchant(e, level, true);
            });
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static List<Item> toItems(ItemStack[] stacks) {
        List<Item> out = new ArrayList<>(stacks.length);
        for (ItemStack s : stacks) out.add(toItem(s));
        return out;
    }
}
