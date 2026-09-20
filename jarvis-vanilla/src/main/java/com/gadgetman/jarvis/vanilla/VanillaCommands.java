package com.gadgetman.jarvis.vanilla;

import com.gadgetman.jarvis.core.platform.Audience;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Brigadier's side of "/jarvis": one greedy argument handed to core's
 * command sink, which decides what the words mean, and tab completion
 * asked of the same sink.
 */
public final class VanillaCommands {

    private VanillaCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, JarvisMod mod) {
        dispatcher.register(literal("jarvis")
                .executes(ctx -> run(mod, ctx.getSource(), List.of()))
                .then(argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggest(mod, ctx, builder))
                        .executes(ctx -> run(mod, ctx.getSource(), split(StringArgumentType.getString(ctx, "args"))))));
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.asList(text.trim().split("\\s+"));
    }

    private static Optional<Owner> asPlayer(JarvisMod mod, CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer)) {
            return Optional.of(mod.platform().players().owner(p.getUUID()));
        }
        return Optional.empty();
    }

    private static Audience audience(JarvisMod mod, CommandSourceStack source) {
        Optional<Owner> player = asPlayer(mod, source);
        if (player.isPresent()) return player.get();
        return text -> {
            if (text != null) source.sendSystemMessage(Component.literal(text));
        };
    }

    private static int run(JarvisMod mod, CommandSourceStack source, List<String> args) {
        if (mod.core() == null) {
            String why = mod.startupError();
            source.sendFailure(Component.literal(why == null
                    ? "Jarvis is not up yet."
                    : "Jarvis could not start: " + why + " (see the server log)"));
            return 0;
        }
        mod.core().commands().jarvis(audience(mod, source), asPlayer(mod, source), args, false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggest(JarvisMod mod, CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        if (mod.core() == null) return builder.buildFuture();
        String typed = builder.getRemaining();
        List<String> args = new ArrayList<>(split(typed));
        boolean openToken = typed.isEmpty() || Character.isWhitespace(typed.charAt(typed.length() - 1));
        if (openToken) args.add("");
        String last = args.get(args.size() - 1);
        List<String> options;
        try {
            options = mod.core().commands().jarvis(audience(mod, ctx.getSource()), asPlayer(mod, ctx.getSource()), args, true);
        } catch (RuntimeException e) {
            return builder.buildFuture();
        }
        SuggestionsBuilder at = builder.createOffset(builder.getStart() + typed.length() - last.length());
        String prefix = last.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(prefix)) at.suggest(option);
        }
        return at.buildFuture();
    }
}
