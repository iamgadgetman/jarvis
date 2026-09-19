package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.world.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Core's {@link Container} over a block's inventory. */
public final class FabricContainer implements Container {

    private final MinecraftServer server;
    private final net.minecraft.world.Container inv;

    public FabricContainer(MinecraftServer server, net.minecraft.world.Container inv) {
        this.server = server;
        this.inv = inv;
    }

    @Override
    public List<Item> contents() {
        List<Item> out = new ArrayList<>(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) out.add(FabricItems.toItem(inv.getItem(i)));
        return out;
    }

    @Override
    public List<Item> add(List<Item> items) {
        List<Item> rest = new ArrayList<>();
        for (Item item : items) {
            if (item.isEmpty()) continue;
            ItemStack stack = FabricItems.toStack(server, item);
            addStack(stack);
            if (!stack.isEmpty()) rest.add(FabricItems.toItem(stack));
        }
        inv.setChanged();
        return rest;
    }

    /** Stack onto matching slots, then fill empty ones; what is left stays in {@code stack}. */
    private void addStack(ItemStack stack) {
        for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
            int room = Math.min(slot.getMaxStackSize(), inv.getMaxStackSize()) - slot.getCount();
            if (room <= 0) continue;
            int moved = Math.min(room, stack.getCount());
            slot.grow(moved);
            stack.shrink(moved);
            inv.setItem(i, slot);
        }
        for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
            if (!inv.getItem(i).isEmpty()) continue;
            int moved = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), inv.getMaxStackSize()));
            inv.setItem(i, stack.copyWithCount(moved));
            stack.shrink(moved);
        }
    }

    @Override
    public int freeSlots() {
        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    @Override
    public int size() {
        return inv.getContainerSize();
    }
}
