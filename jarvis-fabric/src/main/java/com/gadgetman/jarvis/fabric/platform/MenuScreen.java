package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.ui.Menu;
import com.gadgetman.jarvis.core.ui.MenuClick;
import com.gadgetman.jarvis.core.ui.MenuItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * A chest screen drawn from a core {@link Menu}. Every click is reported
 * to the menu's item and nothing moves: the client's guess is undone by a
 * full resync.
 */
public final class MenuScreen extends ChestMenu {

    private final Menu model;
    private final MinecraftServer server;

    public MenuScreen(int id, Inventory playerInventory, Container container, Menu model, MinecraftServer server) {
        super(type(model.rows()), id, playerInventory, container, model.rows());
        this.model = model;
        this.server = server;
    }

    static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer sp) {
            if (slotId >= 0 && slotId < model.size()
                    && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
                MenuItem item = model.at(slotId);
                if (item != null && item.onClick() != null) {
                    item.onClick().accept(new MenuClick(new FabricOwner(server, sp.getUUID()), button == 1));
                }
            }
            // Whatever the client thinks it did, put everything back.
            if (sp.containerMenu == this) sendAllDataToRemote();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(net.minecraft.world.inventory.Slot slot) {
        return false;
    }
}
