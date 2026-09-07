package com.gadgetman.jarvis.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Identity for a Jarvis menu.
 *
 * <p>The menus used to be told apart by comparing the view's title string.
 * That is fragile — a renamed menu silently stops responding to clicks, and
 * any other plugin opening an inventory with a colliding title gets its clicks
 * eaten. Holding the type on the inventory itself is exact.
 */
public class JarvisMenu implements InventoryHolder {

    public enum Type {
        MAIN,
        MINING,
        COMBAT,
        GROUNDSKEEPING,
        BUILDING,
        HOUSEHOLD,
        STEWARD,
        SETTINGS,
        ADMIN,
        SCHEMATIC_PICKER,
        SERVICE_RECORD,
        CONFIRM_CLEAR
    }

    private final Type type;
    private final int page;
    private Inventory inventory;

    public JarvisMenu(Type type) { this(type, 0); }

    public JarvisMenu(Type type, int page) {
        this.type = type;
        this.page = page;
    }

    public Type getType() { return type; }
    public int getPage()  { return page; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
