package com.gadgetman.jarvis.fabric.fake;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * A fake player's keyboard and mouse: where he looks, how he moves, and
 * which buttons are held. Ticked from ServerPlayer.tick by a mixin. Adapted
 * from Carpet's EntityPlayerActionPack (MIT, see THIRD-PARTY-LICENSES.md),
 * trimmed to use, attack and jump.
 */
public class ActionPack {

    private final ServerPlayer player;
    private final Map<ActionType, Action> actions = new EnumMap<>(ActionType.class);

    private BlockPos currentBlock;
    private int blockHitDelay;
    private float curBlockDamageMP;

    private boolean sneaking;
    private boolean sprinting;
    private float forward;
    private float strafing;

    private int itemUseCooldown;

    public ActionPack(ServerPlayer player) {
        this.player = player;
        stopAll();
    }

    public ActionPack start(ActionType type, Action action) {
        Action previous = actions.remove(type);
        if (previous != null) type.stop(player, previous);
        if (action != null) {
            actions.put(type, action);
        }
        return this;
    }

    public boolean isActive(ActionType type) {
        return actions.containsKey(type);
    }

    public ActionPack setSneaking(boolean doSneak) {
        sneaking = doSneak;
        player.setShiftKeyDown(doSneak);
        if (sprinting && sneaking) setSprinting(false);
        return this;
    }

    public ActionPack setSprinting(boolean doSprint) {
        sprinting = doSprint;
        player.setSprinting(doSprint);
        if (sneaking && sprinting) setSneaking(false);
        return this;
    }

    public ActionPack setForward(float value) {
        forward = value;
        return this;
    }

    public ActionPack setStrafing(float value) {
        strafing = value;
        return this;
    }

    public ActionPack look(float yaw, float pitch) {
        player.setYRot(yaw % 360);
        player.setXRot(Mth.clamp(pitch, -90, 90));
        player.setYHeadRot(player.getYRot());
        return this;
    }

    public ActionPack lookAt(Vec3 position) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, position);
        return this;
    }

    public ActionPack stopMovement() {
        setSneaking(false);
        setSprinting(false);
        forward = 0.0F;
        strafing = 0.0F;
        return this;
    }

    public ActionPack stopAll() {
        for (ActionType type : actions.keySet()) type.stop(player, actions.get(type));
        actions.clear();
        return stopMovement();
    }

    /** Once per tick, before the player's own tick. */
    public void onUpdate() {
        Map<ActionType, Boolean> actionAttempts = new HashMap<>();
        actions.values().removeIf(e -> e.done);
        for (Map.Entry<ActionType, Action> e : actions.entrySet()) {
            ActionType type = e.getKey();
            Action action = e.getValue();
            // Skip the attack when a use succeeded this tick, as the client does.
            if (!(actionAttempts.getOrDefault(ActionType.USE, false) && type == ActionType.ATTACK)) {
                Boolean actionStatus = action.tick(this, type);
                if (actionStatus != null) actionAttempts.put(type, actionStatus);
            }
        }
        float vel = sneaking ? 0.3F : 1.0F;
        player.zza = forward * vel;
        player.xxa = strafing * vel;
    }

    static HitResult getTarget(ServerPlayer player) {
        double reach = player.gameMode.isCreative() ? 5 : 4.5f;
        return Tracer.rayTrace(player, 1, reach, false);
    }

    public enum ActionType {
        USE(true) {
            @Override
            boolean execute(ServerPlayer player, Action action) {
                ActionPack ap = ((ActionPackHolder) player).jarvis$actionPack();
                if (ap.itemUseCooldown > 0) {
                    ap.itemUseCooldown--;
                    return true;
                }
                if (player.isUsingItem()) {
                    return true;
                }
                HitResult hit = getTarget(player);
                for (InteractionHand hand : InteractionHand.values()) {
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        player.resetLastActionTime();
                        ServerLevel world = player.level();
                        BlockHitResult blockHit = (BlockHitResult) hit;
                        BlockPos pos = blockHit.getBlockPos();
                        Direction side = blockHit.getDirection();
                        if (pos.getY() < world.getMaxY() - (side == Direction.UP ? 1 : 0) && world.mayInteract(player, pos)) {
                            InteractionResult result = player.gameMode.useItemOn(player, world, player.getItemInHand(hand), hand, blockHit);
                            if (result instanceof InteractionResult.Success success) {
                                if (success.swingSource() == InteractionResult.SwingSource.SERVER_ONLY) {
                                    player.swing(hand, SwingAnimation.DEFAULT, true);
                                }
                                ap.itemUseCooldown = 3;
                                return true;
                            }
                        }
                    }
                    ItemStack handItem = player.getItemInHand(hand);
                    if (player.gameMode.useItem(player, player.level(), handItem, hand).consumesAction()) {
                        ap.itemUseCooldown = 3;
                        return true;
                    }
                }
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action) {
                ActionPack ap = ((ActionPackHolder) player).jarvis$actionPack();
                ap.itemUseCooldown = 0;
                player.releaseUsingItem();
            }
        },
        ATTACK(true) {
            @Override
            boolean execute(ServerPlayer player, Action action) {
                HitResult hit = getTarget(player);
                switch (hit.getType()) {
                    case ENTITY -> {
                        if (!action.isContinuous) {
                            player.attack(((net.minecraft.world.phys.EntityHitResult) hit).getEntity());
                            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                        }
                        player.resetAttackStrengthTicker();
                        player.resetLastActionTime();
                        return true;
                    }
                    case BLOCK -> {
                        ActionPack ap = ((ActionPackHolder) player).jarvis$actionPack();
                        if (ap.blockHitDelay > 0) {
                            ap.blockHitDelay--;
                            return false;
                        }
                        BlockHitResult blockHit = (BlockHitResult) hit;
                        BlockPos pos = blockHit.getBlockPos();
                        Direction side = blockHit.getDirection();
                        if (player.blockActionRestricted(player.level(), pos, player.gameMode.getGameModeForPlayer())) return false;
                        if (ap.currentBlock != null && player.level().getBlockState(ap.currentBlock).isAir()) {
                            ap.currentBlock = null;
                            return false;
                        }
                        BlockState state = player.level().getBlockState(pos);
                        boolean blockBroken = false;
                        if (player.gameMode.getGameModeForPlayer().isCreative()) {
                            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            ap.blockHitDelay = 5;
                            blockBroken = true;
                        } else if (ap.currentBlock == null || !ap.currentBlock.equals(pos)) {
                            if (ap.currentBlock != null) {
                                player.gameMode.handleBlockBreakAction(ap.currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            }
                            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                            boolean notAir = !state.isAir();
                            if (notAir && ap.curBlockDamageMP == 0) {
                                state.attack(player.level(), pos, player);
                            }
                            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1) {
                                ap.currentBlock = null;
                                blockBroken = true;
                            } else {
                                ap.currentBlock = pos;
                                ap.curBlockDamageMP = 0;
                            }
                        } else {
                            ap.curBlockDamageMP += state.getDestroyProgress(player, player.level(), pos);
                            if (ap.curBlockDamageMP >= 1) {
                                player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, side, player.level().getMaxY(), -1);
                                ap.currentBlock = null;
                                ap.blockHitDelay = 5;
                                blockBroken = true;
                            }
                            player.level().destroyBlockProgress(-1, pos, (int) (ap.curBlockDamageMP * 10));
                        }
                        player.resetLastActionTime();
                        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
                        return blockBroken;
                    }
                    default -> {
                        return false;
                    }
                }
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action) {
                ActionPack ap = ((ActionPackHolder) player).jarvis$actionPack();
                if (ap.currentBlock == null) return;
                player.level().destroyBlockProgress(-1, ap.currentBlock, -1);
                player.gameMode.handleBlockBreakAction(ap.currentBlock, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.DOWN, player.level().getMaxY(), -1);
                ap.currentBlock = null;
            }
        },
        JUMP(true) {
            @Override
            boolean execute(ServerPlayer player, Action action) {
                if (action.limit == 1) {
                    if (player.onGround()) player.jumpFromGround();
                    else if (!player.onClimbable()) player.tryToStartFallFlying();
                } else {
                    player.setJumping(true);
                }
                return false;
            }

            @Override
            void inactiveTick(ServerPlayer player, Action action) {
                player.setJumping(false);
            }
        };

        public final boolean preventSpectator;

        ActionType(boolean preventSpectator) {
            this.preventSpectator = preventSpectator;
        }

        abstract boolean execute(ServerPlayer player, Action action);

        void inactiveTick(ServerPlayer player, Action action) {
        }

        void stop(ServerPlayer player, Action action) {
            inactiveTick(player, action);
        }
    }

    public static class Action {
        public boolean done = false;
        public final int limit;
        public final int interval;
        public final int offset;
        private int count;
        private int next;
        private final boolean isContinuous;

        private Action(int limit, int interval, int offset, boolean continuous) {
            this.limit = limit;
            this.interval = interval;
            this.offset = offset;
            next = interval + offset;
            isContinuous = continuous;
        }

        public static Action once() {
            return new Action(1, 1, 0, false);
        }

        public static Action continuous() {
            return new Action(-1, 1, 0, true);
        }

        public static Action interval(int interval) {
            return new Action(-1, interval, 0, false);
        }

        Boolean tick(ActionPack actionPack, ActionType type) {
            next--;
            Boolean cancel = null;
            if (next <= 0) {
                if (interval == 1 && !isContinuous) {
                    // Clear the held state mid-tick so a one-shot has an effect next tick too.
                    if (!type.preventSpectator || !actionPack.player.isSpectator()) {
                        type.inactiveTick(actionPack.player, this);
                    }
                }
                if (!type.preventSpectator || !actionPack.player.isSpectator()) {
                    cancel = type.execute(actionPack.player, this);
                }
                count++;
                if (count == limit) {
                    type.stop(actionPack.player, null);
                    done = true;
                    return cancel;
                }
                next = interval;
            } else {
                if (!type.preventSpectator || !actionPack.player.isSpectator()) {
                    type.inactiveTick(actionPack.player, this);
                }
            }
            return cancel;
        }
    }
}
