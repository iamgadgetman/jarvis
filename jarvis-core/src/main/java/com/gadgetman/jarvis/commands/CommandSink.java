package com.gadgetman.jarvis.commands;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;

import java.util.List;
import java.util.Optional;

/**
 * Where "/jarvis ..." goes. Core owns the parsing and the behaviour; the
 * adapter owns registering the command with its server and calling this.
 */
public interface CommandSink {

    /**
     * Handle "/jarvis {@code args}".
     *
     * @param sender   who typed it: a player or the console
     * @param asPlayer the sender as a player, when they are one
     * @param args     the words after "jarvis"
     * @param tab      true to return completions for the last word instead of acting
     * @return completions when {@code tab}; otherwise empty
     */
    List<String> jarvis(Audience sender, Optional<Owner> asPlayer, List<String> args, boolean tab);
}
