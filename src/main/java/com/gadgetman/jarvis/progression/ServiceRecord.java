package com.gadgetman.jarvis.progression;

/**
 * What one player's Jarvis has actually done for them.
 *
 * <p>Records are per-player: each player has their own NPC, so each earns their
 * own standing. Nothing here is ever decremented — service is a history, not a
 * currency, and there is no way to fall back down the ladder.
 */
public class ServiceRecord {

    private int oresMined;
    private int treesFelled;
    private int cropsHarvested;
    private int fishCaught;
    private int threatsFelled;
    private int blocksPlaced;

    /** Highest rank actually announced, so promotions are reported once. */
    private Rank acknowledged = Rank.HIRED;

    public ServiceRecord() { }

    public ServiceRecord(int ores, int trees, int crops, int fish, int threats, int blocks,
                         Rank acknowledged) {
        this.oresMined      = ores;
        this.treesFelled    = trees;
        this.cropsHarvested = crops;
        this.fishCaught     = fish;
        this.threatsFelled  = threats;
        this.blocksPlaced   = blocks;
        this.acknowledged   = acknowledged == null ? Rank.HIRED : acknowledged;
    }

    /**
     * One number standing for everything he has done.
     *
     * <p>The weights are about effort, not loot value. A tree takes about as
     * long to fell as an ore takes to reach, so both count as one. Building is
     * divided down hard: a single model-planned build can place thousands of
     * blocks, and left at parity it would skip most of the ladder in one order.
     */
    public int service() {
        return oresMined
             + treesFelled
             + cropsHarvested / 2
             + fishCaught
             + threatsFelled * 2
             + blocksPlaced / 50;
    }

    public Rank rank() { return Rank.forService(service()); }

    /** Progress toward the next rank, 0..1. Returns 1 at the top of the ladder. */
    public double progressToNext() {
        Rank current = rank();
        Rank next = current.next();
        if (next == null) return 1.0;
        int from = current.serviceRequired();
        int span = next.serviceRequired() - from;
        if (span <= 0) return 1.0;
        return Math.max(0, Math.min(1, (service() - from) / (double) span));
    }

    public int serviceToNext() {
        Rank next = rank().next();
        return next == null ? 0 : Math.max(0, next.serviceRequired() - service());
    }

    public void add(Discipline discipline, int amount) {
        if (amount <= 0) return;
        switch (discipline) {
            case MINING       -> oresMined      += amount;
            case FORESTRY     -> treesFelled    += amount;
            case FARMING      -> cropsHarvested += amount;
            case FISHING      -> fishCaught     += amount;
            case COMBAT       -> threatsFelled  += amount;
            case CONSTRUCTION -> blocksPlaced   += amount;
        }
    }

    public int count(Discipline discipline) {
        return switch (discipline) {
            case MINING       -> oresMined;
            case FORESTRY     -> treesFelled;
            case FARMING      -> cropsHarvested;
            case FISHING      -> fishCaught;
            case COMBAT       -> threatsFelled;
            case CONSTRUCTION -> blocksPlaced;
        };
    }

    public Rank acknowledged()            { return acknowledged; }
    public void acknowledge(Rank rank)    { this.acknowledged = rank; }

    public int oresMined()      { return oresMined; }
    public int treesFelled()    { return treesFelled; }
    public int cropsHarvested() { return cropsHarvested; }
    public int fishCaught()     { return fishCaught; }
    public int threatsFelled()  { return threatsFelled; }
    public int blocksPlaced()   { return blocksPlaced; }

    /** Admin override: place him at a rank outright. */
    public void setServiceFloor(Rank rank) {
        int needed = rank.serviceRequired() - service();
        if (needed > 0) oresMined += needed;
        acknowledged = rank;
    }

    public enum Discipline {
        MINING, FORESTRY, FARMING, FISHING, COMBAT, CONSTRUCTION
    }
}
