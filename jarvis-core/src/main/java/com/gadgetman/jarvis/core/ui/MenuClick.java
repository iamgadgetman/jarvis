package com.gadgetman.jarvis.core.ui;

import com.gadgetman.jarvis.core.platform.Owner;

/** A click on a {@link MenuItem}. {@code right} is true for a right-click. */
public record MenuClick(Owner viewer, boolean right) {

    public boolean left() {
        return !right;
    }
}
