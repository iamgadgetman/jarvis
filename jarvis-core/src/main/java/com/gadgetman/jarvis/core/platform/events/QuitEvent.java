package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;

public record QuitEvent(Owner who) implements Event { }
