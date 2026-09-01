package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Defender (v0.4.0) - Jarvis the bodyguard.
 *
 * Sentinel's proven pattern: anchor + leash + always-return. Jarvis anchors
 * to the player (bodyguard) or a fixed post (night watch / sentry), engages
 * hostiles according to his stance, chases only within the leash range, and
 * ALWAYS returns to his anchor after combat instead of wandering off.
 *
 * Stances:
 * - PASSIVE:    never fights (stands with you, carries things, judges silently)
 * - DEFENSIVE:  fights only what attacks you or him, or is actively targeting you (default)
 * - AGGRESSIVE: clears any hostile that comes within the engage radius
 *
 * Plus the famous butler move: "Creeper, behind you, sir." — callouts for
 * threats approaching outside the player's field of view.
 */
class Defender {

    enum Stance { PASSIVE, DEFENSIVE, AGGRESSIVE }
    enum Mode { BODYGUARD, SENTRY, PATROL }

    private final Jarvis plugin;
    private final JarvisNPC host;
    private final Player player;
    private final NPC npc;

    private Stance stance;
    private final Mode mode;
    private final Location post;              // Fixed anchor for SENTRY mode

    private java.util.List<Location> patrolRoute = java.util.List.of();
    private int patrolIndex = 0;

    private LivingEntity target = null;
    private boolean returning = false;
    private long lastAttackMs = 0;
    private boolean navBusy = false;          // We issued a nav target this engagement

    // Retaliation memory: who attacked us/the player recently (uuid -> expiry ms)
    private final Map<UUID, Long> threats = new HashMap<>();
    // Callout cooldowns per entity
    private final Map<UUID, Long> calloutCooldowns = new HashMap<>();

    // Config
    private final double engageRadius;
    private final double leashRange;
    private final double attackDamage;
    private final long attackCooldownMs;
    private final boolean callouts;

    private static final double ATTACK_REACH = 2.7;
    private static final long THREAT_MEMORY_MS = 30_000;
    private static final long CALLOUT_COOLDOWN_MS = 8_000;
    private static final double CALLOUT_RADIUS = 10.0;

    Defender(JarvisNPC host, Player player, NPC npc, Stance stance, Mode mode) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.npc = npc;
        this.stance = stance;
        this.mode = mode;
        this.post = mode == Mode.SENTRY
                ? host.getCurrentLocation(npc).getBlock().getLocation().add(0.5, 0, 0.5) : null;

        var cfg = plugin.getConfig();
        this.engageRadius = cfg.getDouble("defender.engage-radius", 12.0);
        this.leashRange = cfg.getDouble("defender.leash-range", 12.0);
        this.attackDamage = cfg.getDouble("defender.attack-damage", 7.0);
        this.attackCooldownMs = cfg.getLong("defender.attack-cooldown-ticks", 12L) * 50L;
        this.callouts = cfg.getBoolean("defender.callouts", true);
    }

    void setPatrolRoute(java.util.List<Location> route) {
        this.patrolRoute = route;
    }

    Stance getStance() {
        return stance;
    }

    Mode getMode() {
        return mode;
    }

    void setStance(Stance stance) {
        this.stance = stance;
        if (stance == Stance.PASSIVE) {
            disengage();
        }
    }

    // ==================== LIFECYCLE ====================

    void start() {
        host.applyNavigatorDefaults(npc, null);
        host.giveGuardEquipment(npc);

        String where = switch (mode) {
            case SENTRY -> "Holding this position";
            case PATROL -> "Walking the rounds (" + patrolRoute.size() + " waypoints)";
            default -> "At your side";
        };
        String posture = switch (stance) {
            case PASSIVE -> "observing only";
            case DEFENSIVE -> "engaging anything that starts trouble";
            case AGGRESSIVE -> "weapons free";
        };
        host.say(player, where + ", sir — " + posture + ".");

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!npc.isSpawned() || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                Location npcLoc = host.getCurrentLocation(npc);
                host.pickupNearbyItems(npc, npcLoc);
                tick(npcLoc);
            }
        };
        task.runTaskTimer(plugin, 0L, 10L);
        host.registerTask(player, task);
    }

    /** Called by JarvisNPC's damage listener: someone hurt the player or Jarvis. */
    void recordThreat(Entity damager) {
        if (damager instanceof LivingEntity living && damager instanceof Enemy) {
            threats.put(living.getUniqueId(), System.currentTimeMillis() + THREAT_MEMORY_MS);
        }
    }

    // ==================== MAIN LOOP ====================

    private void tick(Location npcLoc) {
        threats.values().removeIf(expiry -> expiry < System.currentTimeMillis());

        Location anchor = switch (mode) {
            case SENTRY -> post;
            case PATROL -> patrolRoute.isEmpty() ? player.getLocation()
                    : patrolRoute.get(patrolIndex % patrolRoute.size());
            default -> player.getLocation();
        };
        if (anchor.getWorld() != npcLoc.getWorld()) {
            // Player changed worlds — bodyguard catches up, sentry stays
            if (mode == Mode.BODYGUARD) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(player.getLocation(),
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            return;
        }

        if (callouts && mode == Mode.BODYGUARD) {
            tickCallouts();
        }

        // ---- In combat ----
        if (target != null) {
            if (!target.isValid() || target.isDead() || target.getWorld() != npcLoc.getWorld()) {
                disengage();
            } else if (target.getLocation().distance(anchor) > leashRange + 6) {
                // Target fled beyond the leash — let it go, return to post
                disengage();
            } else {
                fight(npcLoc);
                return;
            }
        }

        // ---- Pick a new target ----
        if (stance != Stance.PASSIVE) {
            LivingEntity picked = selectTarget(anchor, npcLoc);
            if (picked != null) {
                target = picked;
                navBusy = false;
                return;
            }
        }

        // ---- No combat: hold formation ----
        // Patrol: reached the waypoint? move on to the next
        if (mode == Mode.PATROL && !patrolRoute.isEmpty()
                && npcLoc.distance(anchor) <= 2.5) {
            patrolIndex = (patrolIndex + 1) % patrolRoute.size();
            anchor = patrolRoute.get(patrolIndex);
        }

        double distToAnchor = npcLoc.distance(anchor);
        if (returning && distToAnchor <= 3.0) {
            returning = false;
            npc.getNavigator().cancelNavigation();
        }
        if (distToAnchor > (mode == Mode.BODYGUARD ? 4.0 : mode == Mode.PATROL ? 1.5 : 3.0)) {
            if (!npc.getNavigator().isNavigating()) {
                if (mode == Mode.BODYGUARD && distToAnchor > 30) {
                    npc.teleport(player.getLocation(),
                            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                } else {
                    npc.getNavigator().setTarget(anchor);
                }
            }
        } else if (npc.getNavigator().isNavigating() && distToAnchor <= 2.0) {
            npc.getNavigator().cancelNavigation();
        }
    }

    // ==================== COMBAT ====================

    private void fight(Location npcLoc) {
        double dist = npcLoc.distance(target.getLocation());

        if (dist > ATTACK_REACH) {
            if (!navBusy || !npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(target, true);
                navBusy = true;
            }
            return;
        }

        npc.getNavigator().cancelNavigation();
        navBusy = false;
        npc.faceLocation(target.getLocation());

        long now = System.currentTimeMillis();
        if (now - lastAttackMs >= attackCooldownMs) {
            lastAttackMs = now;
            if (npc.getEntity() instanceof LivingEntity le) {
                le.swingMainHand();
            }
            target.damage(attackDamage, npc.getEntity());
            npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);

            if (target.isDead() || !target.isValid()) {
                host.sayQuiet(player, "Threat neutralised.");
                disengage();
            }
        }
    }

    private void disengage() {
        target = null;
        navBusy = false;
        returning = true;
        npc.getNavigator().cancelNavigation();
    }

    /** Choose the most pressing hostile per stance. Creepers first — they explode. */
    private LivingEntity selectTarget(Location anchor, Location npcLoc) {
        if (npc.getEntity() == null) return null;

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity e : npc.getEntity().getNearbyEntities(engageRadius, engageRadius, engageRadius)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity living)) continue;
            if (living.isDead() || !living.isValid()) continue;
            if (living.getLocation().distance(anchor) > engageRadius) continue;

            boolean isThreat = threats.containsKey(living.getUniqueId())
                    || (living instanceof org.bukkit.entity.Mob mob && player.equals(mob.getTarget()));

            if (stance == Stance.DEFENSIVE && !isThreat) continue;

            double score = living.getLocation().distance(anchor);
            if (living instanceof Creeper) score -= 100;   // always deal with creepers first
            else if (isThreat) score -= 50;                 // active attackers next

            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    // ==================== CALLOUTS ====================

    /** "Creeper, behind you, sir." — warn about hostiles outside the player's view cone. */
    private void tickCallouts() {
        long now = System.currentTimeMillis();
        calloutCooldowns.values().removeIf(t -> t < now);

        Vector look = player.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 0.01) return;
        look.normalize();

        for (Entity e : player.getNearbyEntities(CALLOUT_RADIUS, 6, CALLOUT_RADIUS)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity living)) continue;
            if (living.isDead() || calloutCooldowns.containsKey(living.getUniqueId())) continue;

            Vector toMob = living.getLocation().toVector()
                    .subtract(player.getLocation().toVector()).setY(0);
            if (toMob.lengthSquared() < 0.01) continue;
            toMob.normalize();

            // Behind or well outside the view cone (dot < 0.2 ≈ more than ~78° off-axis)
            if (look.dot(toMob) < 0.2) {
                String name = e.getType().name().toLowerCase().replace('_', ' ');
                boolean urgent = living instanceof Creeper;
                if (urgent || living.getLocation().distance(player.getLocation()) < 6) {
                    host.say(player, (urgent ? "Creeper, " : "A " + name + ", ")
                            + "behind you, sir" + (urgent ? "!" : "."));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
                    calloutCooldowns.put(living.getUniqueId(), now + CALLOUT_COOLDOWN_MS);
                }
            }
        }
    }
}
