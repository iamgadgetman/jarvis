package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.world.Item;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Core's {@link Container} over a block inventory. */
public final class PaperContainer implements Container {

    private final Inventory inv;

    public PaperContainer(Inventory inv) {
        this.inv = inv;
    }

    @Override
    public List<Item> contents() {
        return PaperItems.toItems(inv.getContents());
    }

    @Override
    public List<Item> add(List<Item> items) {
        List<Item> rest = new ArrayList<>();
        for (Item item : items) {
            if (item.isEmpty()) continue;
            for (ItemStack left : inv.addItem(PaperItems.toStack(item)).values()) {
                rest.add(PaperItems.toItem(left));
            }
        }
        return rest;
    }

    @Override
    public int freeSlots() {
        int free = 0;
        for (ItemStack s : inv.getContents()) {
            if (s == null || s.getType() == Material.AIR) free++;
        }
        return free;
    }

    @Override
    public int size() {
        return inv.getSize();
    }
}
