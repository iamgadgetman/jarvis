package com.gadgetman.jarvis.core.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An item stack as core sees it: enough for kit, loot, deposits and menus.
 *
 * <p>Enchantments are keyed by namespaced id. {@code marker} is an opaque
 * string an adapter stores in whatever per-item data its platform offers, so
 * core can recognise an item it made (the controller bell) without knowing
 * how the platform tagged it.
 */
public record Item(String id, int count, Map<String, Integer> enchants,
                   String displayName, List<String> lore, String marker, boolean unbreakable) {

    public static final Item EMPTY = new Item(Ids.AIR, 0, Map.of(), null, List.of(), null, false);

    public Item {
        enchants = Map.copyOf(enchants);
        lore = List.copyOf(lore);
    }

    public static Item of(String id) {
        return of(id, 1);
    }

    public static Item of(String id, int count) {
        return new Item(id, count, Map.of(), null, List.of(), null, false);
    }

    public Item withCount(int n) {
        return new Item(id, n, enchants, displayName, lore, marker, unbreakable);
    }

    public Item enchant(String enchantId, int level) {
        Map<String, Integer> next = new HashMap<>(enchants);
        next.put(enchantId, level);
        return new Item(id, count, next, displayName, lore, marker, unbreakable);
    }

    public Item named(String name) {
        return new Item(id, count, enchants, name, lore, marker, unbreakable);
    }

    public Item withLore(List<String> lines) {
        return new Item(id, count, enchants, displayName, lines, marker, unbreakable);
    }

    public Item marked(String marker) {
        return new Item(id, count, enchants, displayName, lore, marker, unbreakable);
    }

    public Item unbreakable(boolean value) {
        return new Item(id, count, enchants, displayName, lore, marker, value);
    }

    public int enchantLevel(String enchantId) {
        return enchants.getOrDefault(enchantId, 0);
    }

    public boolean hasMarker(String marker) {
        return marker != null && marker.equals(this.marker);
    }

    public boolean isEmpty() {
        return id == null || count <= 0 || Ids.AIR.equals(id);
    }

    public boolean is(String id) {
        return this.id.equals(id);
    }
}
