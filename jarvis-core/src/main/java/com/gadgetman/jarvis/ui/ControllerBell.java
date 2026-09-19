package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;

import java.util.List;

/** The bell that opens the menu: how it is made, and how it is recognised. */
public final class ControllerBell {

    private ControllerBell() { }

    /** The marker the bell carries, as {@link Item#marker()}. */
    public static final String MARKER = "controller";

    public static Item create() {
        return Item.of(Ids.BELL)
                .named("§6Jarvis Controller")
                .withLore(List.of("§7Right-click to open menu", "§7Works when placed too!"))
                .marked(MARKER);
    }

    public static boolean isController(Item item) {
        return item != null && item.is(Ids.BELL) && MARKER.equals(item.marker());
    }
}
