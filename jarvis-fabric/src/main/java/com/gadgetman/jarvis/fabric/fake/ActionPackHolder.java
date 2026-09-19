package com.gadgetman.jarvis.fabric.fake;

/** Implemented onto ServerPlayer by a mixin, so every player carries an action pack. */
public interface ActionPackHolder {
    ActionPack jarvis$actionPack();
}
