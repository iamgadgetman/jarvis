package com.gadgetman.jarvis.core.ui;

import com.gadgetman.jarvis.core.world.Item;

import java.util.function.Consumer;

/**
 * One entry in a {@link Menu}: what it looks like and what it does.
 *
 * @param onClick run on the server thread when the viewer clicks it; null for
 *                a label that does nothing
 */
public record MenuItem(Item icon, Consumer<MenuClick> onClick) {

    public static MenuItem label(Item icon) {
        return new MenuItem(icon, null);
    }
}
