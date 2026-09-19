package com.gadgetman.jarvis.core.platform.events;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;

/** A player is going through a portal. {@code to} may be null when not yet known. */
public record PortalEvent(Owner who, Site from, Site to) implements Event { }
