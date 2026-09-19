package com.gadgetman.jarvis.fabric.spike.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a fake connection give itself a channel so it counts as open. */
@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    void setChannel(Channel channel);
}
