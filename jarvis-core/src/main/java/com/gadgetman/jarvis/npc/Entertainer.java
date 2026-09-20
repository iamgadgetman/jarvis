package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Look;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.Random;

/**
 * Entertainer (v0.7.0) - the dance module.
 *
 * Inspired by the piglin victory dance: spins, little hops, arm swings, and
 * note particles. Nothing in the API plays real dance animations for player
 * NPCs, so this is choreography from primitives — rotation, velocity hops,
 * swings, particles, and note-block sounds. It reads surprisingly well.
 *
 * Two entry points: dance() (the full number, on command) and celebrate()
 * (a two-second bop used for milestones: mine complete, recovery delivered,
 * harvest done).
 */
public final class Entertainer {

    private Entertainer() { }

    private static final Random RANDOM = new Random();

    private static final String[] AFTER_DANCE = {
            "I trust that was satisfactory, sir.",
            "One does keep a few moves in reserve, sir.",
            "The conservatory of my youth, sir. It stays with you.",
            "Never let it be said I lack range, sir."
    };

    /** The full number: ~6 seconds of choreography. Registers as the active task. */
    public static void dance(ButlerHost host, Owner player) {
        host.stopTask(player);
        host.say(player, "Very well, sir. Observe.");
        perform(host, player, 6 * 20, true);
    }

    /** Milestone bop: short, does not interrupt narration or claim the active task. */
    public static void celebrate(ButlerHost host, Owner player) {
        if (!host.celebrationsEnabled()) return;
        if (!host.butler(player).isSpawned()) return;
        perform(host, player, 2 * 20, false);
    }

    private static void perform(ButlerHost host, Owner player, int durationTicks, boolean bow) {
        Butler butler = host.butler(player);

        Task routine = host.platform().scheduler().every(0L, 2L, new java.util.function.Consumer<Task>() {
            int tick = 0;
            final float baseYaw = butler.isSpawned() ? butler.look().yaw() : 0f;

            @Override
            public void accept(Task self) {
                if (!butler.isSpawned() || !player.isOnline() || tick >= durationTicks) {
                    self.cancel();
                    if (bow) host.taskDone(player, self);
                    if (butler.isSpawned() && bow) {
                        // Face the audience, one last swing (a bow, in spirit)
                        butler.lookAt(player.pos());
                        butler.swing();
                        host.say(player, AFTER_DANCE[RANDOM.nextInt(AFTER_DANCE.length)]);
                    }
                    return;
                }

                Vec3 loc = butler.pos();
                World world = butler.world().orElse(null);

                // Spin: quarter-ish turns on a beat
                if (tick % 4 == 0) {
                    float yaw = baseYaw + (tick * 37f) % 360f;
                    butler.setLook(new Look(yaw, RANDOM.nextBoolean() ? -10f : 10f));
                }

                // Hop on the off-beat
                if (tick % 10 == 6 && butler.isOnGround()) {
                    butler.setVelocity(new Vec3(0, 0.32, 0));
                }

                // Arm swings, alternating
                if (tick % 6 == 0) {
                    if ((tick / 6) % 2 == 0) butler.swing();
                    else butler.swingOffHand();
                }

                // Notes floating up + a note-block melody of sorts
                if (tick % 5 == 0 && world != null) {
                    world.particle(loc.add(0, 2.3, 0), Ids.PARTICLE_NOTE, 2, 0.3);
                    world.sound(loc, Ids.SOUND_BLOCK_NOTE_BLOCK_BIT, 0.7f, 0.6f + RANDOM.nextFloat() * 1.2f);
                }

                tick += 2;
            }
        });
        if (bow) {
            host.registerTask(player, routine);
        }
    }
}
