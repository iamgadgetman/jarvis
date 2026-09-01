package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

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
class Entertainer {

    private static final Random RANDOM = new Random();

    private static final String[] AFTER_DANCE = {
            "I trust that was satisfactory, sir.",
            "One does keep a few moves in reserve, sir.",
            "The conservatory of my youth, sir. It stays with you.",
            "Never let it be said I lack range, sir."
    };

    /** The full number: ~6 seconds of choreography. Registers as the active task. */
    static void dance(JarvisNPC host, Player player, NPC npc) {
        host.stopTask(player);
        host.say(player, "Very well, sir. Observe.");
        perform(host, player, npc, 6 * 20, true);
    }

    /** Milestone bop: short, does not interrupt narration or claim the active task. */
    static void celebrate(JarvisNPC host, Player player, NPC npc) {
        if (!host.getPlugin().getConfig().getBoolean("steward.celebrations", true)) return;
        if (npc == null || !npc.isSpawned()) return;
        perform(host, player, npc, 2 * 20, false);
    }

    private static void perform(JarvisNPC host, Player player, NPC npc, int durationTicks, boolean bow) {
        Jarvis plugin = host.getPlugin();

        BukkitRunnable routine = new BukkitRunnable() {
            int tick = 0;
            float baseYaw = npc.isSpawned() ? npc.getEntity().getLocation().getYaw() : 0f;

            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline() || tick >= durationTicks) {
                    cancel();
                    if (bow) host.taskDone(player, this);
                    if (npc.isSpawned() && bow) {
                        // Face the audience, one last swing (a bow, in spirit)
                        npc.faceLocation(player.getLocation());
                        if (npc.getEntity() instanceof LivingEntity le) le.swingMainHand();
                        host.say(player, AFTER_DANCE[RANDOM.nextInt(AFTER_DANCE.length)]);
                    }
                    return;
                }

                Location loc = npc.getEntity().getLocation();

                // Spin: quarter-ish turns on a beat
                if (tick % 4 == 0) {
                    float yaw = baseYaw + (tick * 37f) % 360f;
                    npc.getEntity().setRotation(yaw, RANDOM.nextBoolean() ? -10f : 10f);
                }

                // Hop on the off-beat
                if (tick % 10 == 6 && npc.getEntity().isOnGround()) {
                    npc.getEntity().setVelocity(new Vector(0, 0.32, 0));
                }

                // Arm swings, alternating
                if (tick % 6 == 0 && npc.getEntity() instanceof LivingEntity le) {
                    if ((tick / 6) % 2 == 0) le.swingMainHand();
                    else le.swingOffHand();
                }

                // Notes floating up + a note-block melody of sorts
                if (tick % 5 == 0) {
                    loc.getWorld().spawnParticle(Particle.NOTE,
                            loc.clone().add(0, 2.3, 0), 2, 0.35, 0.25, 0.35);
                    loc.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BIT,
                            0.7f, 0.6f + RANDOM.nextFloat() * 1.2f);
                }

                tick += 2;
            }
        };

        routine.runTaskTimer(plugin, 0L, 2L);
        if (bow) {
            host.registerTask(player, routine);
        }
    }
}
