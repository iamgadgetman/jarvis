package com.gadgetman.jarvis.onboarding;

import com.gadgetman.jarvis.core.platform.Owner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Where services report a {@link Milestone}, and where the tutorial listens.
 * Reporting is cheap and never throws: a listener's failure is not the
 * reporter's problem. Server thread.
 */
public final class Milestones {

    /** Reports go nowhere; the default for services built before onboarding exists. */
    public static final Milestones NONE = new Milestones();

    private final List<BiConsumer<Owner, Milestone>> listeners = new CopyOnWriteArrayList<>();

    public void listen(BiConsumer<Owner, Milestone> listener) {
        if (this != NONE) listeners.add(listener);
    }

    public void reached(Owner owner, Milestone milestone) {
        if (owner == null) return;
        for (BiConsumer<Owner, Milestone> l : listeners) {
            try {
                l.accept(owner, milestone);
            } catch (RuntimeException ignored) {
                // The tutorial is a nicety; the job it watched has already happened.
            }
        }
    }
}
