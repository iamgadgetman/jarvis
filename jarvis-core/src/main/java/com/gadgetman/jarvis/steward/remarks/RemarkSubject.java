package com.gadgetman.jarvis.steward.remarks;

/**
 * What a remark is <i>about</i>.
 *
 * <p>Cooldowns are keyed on this rather than on the wording, and that is the
 * single most important decision in the feature. A cooldown on the sentence is
 * no cooldown at all: seventy-two iron ore and sixty-eight iron ore are the
 * same observation twice, and anything that generates phrasing will happily
 * rephrase its way around a limit set on the text.
 */
public enum RemarkSubject {

    /** A quantity of something worth having. */
    HOARD,

    /** A quantity of something not worth having, which is its own kind of funny. */
    BULK_HAUL,

    /** Nowhere left to put anything. */
    FULL_BAGS,

    /** Deep enough that it is worth mentioning. */
    DEPTH,

    /** High enough that falling is the main risk. */
    ALTITUDE,

    /** Dark enough to spawn things. */
    DARKNESS,

    /** Night, outdoors. */
    NIGHTFALL,

    /** Lightning, which is a different matter from rain. */
    STORM,

    /** A long way from the bed they will respawn at. */
    FAR_FROM_HOME,

    /** Somewhere that is not the overworld. */
    ELSEWHERE,

    /** Levels going unspent. */
    EXPERIENCE,

    /** Company, of the wrong sort, and he has not been asked to deal with it. */
    COMPANY
}
