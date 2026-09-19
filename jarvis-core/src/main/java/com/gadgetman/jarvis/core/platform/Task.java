package com.gadgetman.jarvis.core.platform;

/** A scheduled piece of work that can be cancelled. */
public interface Task {

    void cancel();

    boolean isCancelled();
}
