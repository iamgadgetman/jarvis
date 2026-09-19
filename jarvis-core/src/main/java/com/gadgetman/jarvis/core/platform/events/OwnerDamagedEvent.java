package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.Owner;

import java.util.Optional;

/** Something hurt a player. */
public record OwnerDamagedEvent(Owner who, Optional<Entity> attacker, double amount) implements Event { }
