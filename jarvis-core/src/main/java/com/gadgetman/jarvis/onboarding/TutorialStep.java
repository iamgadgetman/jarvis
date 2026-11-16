package com.gadgetman.jarvis.onboarding;

/**
 * The tutorial's steps, in order (FB-11). Each says what to do and finishes
 * when its {@link Milestone} is reported for the player. Only the build step
 * needs a working AI: "follow me", "mine 5 iron", "stop" and "deposit" are
 * answered by the fast path (FB-01) without one.
 */
public enum TutorialStep {
    RING_BELL(Milestone.BELL, false,
            "Ring the bell in your hand: right-click with it.",
            "There's the menu. Everything I do is a button in there."),
    SUMMON(Milestone.SUMMONED, false,
            "Press Summon in that menu, or type /jarvis summon.",
            "At your service. That's me."),
    FOLLOW(Milestone.FOLLOWING, false,
            "Now just talk to me. Say \"jarvis, follow me\" in chat.",
            "Right behind you. You can say most things in plain words."),
    CHEST(Milestone.CHEST, false,
            "Look at a chest and type /jarvis chest, or press Set deposit chest on the bell.",
            "Noted. That's where your spoils go."),
    MINE(Milestone.MINING, false,
            "Ask me for something. \"jarvis, mine 5 iron\" will do.",
            "Off I go. I'll pick up what I dig."),
    DEPOSIT(Milestone.DEPOSIT, false,
            "Say \"jarvis, stop\", then \"jarvis, deposit\".",
            "Delivered to your chest, or on the way."),
    BUILD(Milestone.UNDO, true,
            "Last one: ask me to \"build a small hut\", say yes to the outline, then type /jarvis undo.",
            "Gone again. Every build I make can be undone like that.");

    private final Milestone completesOn;
    private final boolean needsAi;
    private final String instruction;
    private final String praise;

    TutorialStep(Milestone completesOn, boolean needsAi, String instruction, String praise) {
        this.completesOn = completesOn;
        this.needsAi = needsAi;
        this.instruction = instruction;
        this.praise = praise;
    }

    public Milestone completesOn() { return completesOn; }
    public boolean needsAi() { return needsAi; }
    public String instruction() { return instruction; }
    public String praise() { return praise; }

    /** 1-based, as players and the database see it. */
    public int number() { return ordinal() + 1; }

    /** The step with this number, or null past the end. */
    public static TutorialStep byNumber(int n) {
        TutorialStep[] all = values();
        return n >= 1 && n <= all.length ? all[n - 1] : null;
    }
}
