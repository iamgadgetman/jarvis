package com.gadgetman.jarvis.fabric.spike;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.fabric.spike.fake.FakePlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * <pre>
 * /jspike spawn [name]      a fake player (Jarvis, in his uniform) appears where the caller stands
 * /jspike goto  x y z       walks there along an A* path
 * /jspike dig   x y z       walks within reach and breaks that block
 * /jspike stop              drops whatever he is doing
 * /jspike status            what he is doing and where he is
 * /jspike kill              he leaves
 * </pre>
 */
public final class SpikeCommands {

    private SpikeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("jspike")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(literal("spawn")
                        .executes(c -> spawn(c, "Jarvis"))
                        .then(argument("name", StringArgumentType.word())
                                .executes(c -> spawn(c, StringArgumentType.getString(c, "name")))))
                .then(literal("goto")
                        .then(argument("pos", BlockPosArgument.blockPos())
                                .executes(SpikeCommands::goTo)))
                .then(literal("dig")
                        .then(argument("pos", BlockPosArgument.blockPos())
                                .executes(SpikeCommands::dig)))
                .then(literal("stop").executes(SpikeCommands::stop))
                .then(literal("status").executes(SpikeCommands::status))
                .then(literal("kill").executes(SpikeCommands::kill)));
    }

    private static int spawn(CommandContext<CommandSourceStack> c, String name) {
        CommandSourceStack source = c.getSource();
        if (SpikeMod.current() != null) {
            source.sendFailure(Component.literal(SpikeMod.current().getName().getString() + " is already here; /jspike kill first"));
            return 0;
        }
        if (SpikeMod.isSpawning()) {
            source.sendFailure(Component.literal("Still fetching the last one's uniform; a moment"));
            return 0;
        }
        if (source.getServer().getPlayerList().getPlayerByName(name) != null) {
            source.sendFailure(Component.literal("Someone called " + name + " is already online"));
            return 0;
        }
        Vec3 pos = source.getPosition();
        Vec2 rot = source.getRotation();
        SpikeMod.setSpawning(true);
        FakePlayer.spawn(name, source.getServer(), source.getLevel(), pos, rot.y, rot.x, GameType.SURVIVAL, fake -> {
            SpikeMod.setCurrent(fake);
            if (fake == null) {
                source.sendFailure(Component.literal(name + " could not be spawned; see the server log"));
            } else {
                source.sendSuccess(() -> Component.literal(name + " has arrived"), true);
            }
        });
        return 1;
    }

    private static FakePlayer requireFake(CommandSourceStack source) {
        FakePlayer fake = SpikeMod.current();
        if (fake == null) source.sendFailure(Component.literal("Nobody to command; /jspike spawn first"));
        return fake;
    }

    private static BlockPos pos(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        net.minecraft.core.BlockPos p = BlockPosArgument.getLoadedBlockPos(c, "pos");
        return new BlockPos(p.getX(), p.getY(), p.getZ());
    }

    private static int goTo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        FakePlayer fake = requireFake(c.getSource());
        if (fake == null) return 0;
        BlockPos target = pos(c);
        fake.driver().goTo(target);
        c.getSource().sendSuccess(() -> Component.literal(fake.getName().getString() + ": " + fake.driver().status()), false);
        return 1;
    }

    private static int dig(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        FakePlayer fake = requireFake(c.getSource());
        if (fake == null) return 0;
        BlockPos target = pos(c);
        fake.driver().dig(target);
        c.getSource().sendSuccess(() -> Component.literal(fake.getName().getString() + ": " + fake.driver().status()), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        FakePlayer fake = requireFake(c.getSource());
        if (fake == null) return 0;
        fake.driver().stop();
        c.getSource().sendSuccess(() -> Component.literal(fake.getName().getString() + " stopped"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        FakePlayer fake = requireFake(c.getSource());
        if (fake == null) return 0;
        Vec3 p = fake.position();
        String where = String.format("%.1f %.1f %.1f", p.x, p.y, p.z);
        c.getSource().sendSuccess(() -> Component.literal(fake.getName().getString() + " at " + where
                + (fake.onGround() ? " on ground" : " in the air") + ": " + fake.driver().status()), false);
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> c) {
        FakePlayer fake = requireFake(c.getSource());
        if (fake == null) return 0;
        fake.driver().stop();
        fake.kill(Component.literal("Dismissed"));
        SpikeMod.setCurrent(null);
        c.getSource().sendSuccess(() -> Component.literal(fake.getName().getString() + " has left"), false);
        return 1;
    }
}
