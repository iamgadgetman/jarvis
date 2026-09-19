package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.world.Ids;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

/** Resolves core's namespaced ids to Bukkit's registry constants, and back. */
public final class PaperItems {

    private PaperItems() { }

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
}
