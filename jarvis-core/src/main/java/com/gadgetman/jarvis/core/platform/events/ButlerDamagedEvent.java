package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Entity;

import java.util.Optional;
import java.util.UUID;

/** Something hurt a butler; {@code ownerId} is whose. */
public record ButlerDamagedEvent(UUID ownerId, Optional<Entity> attacker, double amount) implements Event { }
