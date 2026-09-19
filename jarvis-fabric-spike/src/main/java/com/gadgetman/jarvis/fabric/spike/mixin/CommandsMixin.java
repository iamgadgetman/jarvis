package com.gadgetman.jarvis.fabric.spike.mixin;

import com.gadgetman.jarvis.fabric.spike.SpikeCommands;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers /jspike without Fabric API, the way Carpet registers its commands. */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void jarvis$register(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        SpikeCommands.register(this.dispatcher);
    }
}
