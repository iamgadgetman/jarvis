package com.gadgetman.jarvis.core.platform;

/** A registered event handler that can be withdrawn. */
public interface Subscription {

    void cancel();
}
