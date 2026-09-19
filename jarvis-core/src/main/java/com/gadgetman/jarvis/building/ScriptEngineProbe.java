package com.gadgetman.jarvis.building;

/**
 * Whether GraalJS is on the classpath, asked without touching any class
 * that mentions it.
 *
 * <p>This has to be its own class. {@link ScriptBuildPlanner} catches
 * polyglot exceptions, so linking it makes the JVM load those types, and a
 * server without the engine (a Fabric server, or a Paper server whose
 * library download failed) gets a NoClassDefFoundError before any check
 * inside the planner can run. Nothing here names a polyglot type.
 */
public final class ScriptEngineProbe {

    private ScriptEngineProbe() {
    }

    public static boolean isAvailable() {
        try {
            Class.forName("org.graalvm.polyglot.Context", false, ScriptEngineProbe.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
