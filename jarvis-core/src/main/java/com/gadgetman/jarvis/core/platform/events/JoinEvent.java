package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;

public record JoinEvent(Owner who, boolean firstJoin) implements Event { }
