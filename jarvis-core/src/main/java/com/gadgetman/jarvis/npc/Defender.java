package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Site;
import com.gadgetman.jarvis.core.platform.Task;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;
import com.gadgetman.jarvis.core.world.WorldId;
import com.gadgetman.jarvis.npc.combat.Armament;
import com.gadgetman.jarvis.npc.combat.Engagement;
import com.gadgetman.jarvis.npc.combat.WeaponDoctrine;
import com.gadgetman.jarvis.progression.Rank;
import com.gadgetman.jarvis.progression.ServiceRecord;

import java.util.HashMap;
import java.util.List;
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
public class Defender {

    public enum Stance { PASSIVE, DEFENSIVE, AGGRESSIVE }
    public enum Mode { BODYGUARD, SENTRY, PATROL }

    private final ButlerHost host;
    private final Owner player;
    private final Butler butler;

    private Stance stance;
    private final Mode mode;
    private final Vec3 post;                  // Fixed anchor for SENTRY mode

    private List<Site> patrolRoute = List.of();
    private int patrolIndex = 0;

    private Entity target = null;
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
    private final boolean archery;
    private final long shotCooldownMs;
    private final double arrowDamage;
    private long lastShotMs = 0;

    // Reach used to live here as a single constant shared by every weapon.
    // It now belongs to the weapon -- see Armament.reach().
    private static final long THREAT_MEMORY_MS = 30_000;
    private static final long CALLOUT_COOLDOWN_MS = 8_000;
    private static final double CALLOUT_RADIUS = 10.0;

    public Defender(ButlerHost host, Owner player, Stance stance, Mode mode) {
        this.host = host;
        this.player = player;
        this.butler = host.butler(player);
        this.stance = stance;
        this.mode = mode;
        this.post = mode == Mode.SENTRY ? butler.pos().block().standing() : null;

        Config cfg = host.config();
        this.engageRadius = cfg.getDouble("defender.engage-radius", 12.0);
        this.leashRange = cfg.getDouble("defender.leash-range", 12.0);
        this.attackDamage = cfg.getDouble("defender.attack-damage", 7.0);
        this.attackCooldownMs = cfg.getLong("defender.attack-cooldown-ticks", 12L) * 50L;
        this.callouts = cfg.getBoolean("defender.callouts", true);
        this.archery = cfg.getBoolean("defender.archery.enabled", true);
        this.shotCooldownMs = cfg.getLong("defender.archery.draw-ticks", 24L) * 50L;
        this.arrowDamage = cfg.getDouble("defender.archery.arrow-damage", 6.0);
    }

    public void setPatrolRoute(List<Site> route) {
        this.patrolRoute = route;
    }

    public Stance getStance() {
        return stance;
    }

    public Mode getMode() {
        return mode;
    }

    public void setStance(Stance stance) {
        this.stance = stance;
        if (stance == Stance.PASSIVE) {
            disengage();
        }
    }

    // ==================== LIFECYCLE ====================

    public void start() {
        butler.applyNavigationDefaults(null);
        host.giveGuardEquipment(player);

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

        Task task = host.platform().scheduler().every(0L, 10L, self -> {
            if (!butler.isSpawned() || !player.isOnline()) {
                self.cancel();
                host.taskDone(player, self);
                return;
            }
            Vec3 npcLoc = butler.pos();
            host.pickupNearbyItems(player, npcLoc);
            tick(npcLoc);
        });
        host.registerTask(player, task);
    }

    /** How far he'll throw, and how often. Beyond this he closes on foot. */
    private static final double THROW_RANGE = 24.0;
    private static final long   THROW_COOLDOWN_MS = 2500;
    private long lastThrowMs = 0;

    /**
     * Throw the trident at something out of reach.
     *
     * <p>Thrown only while he is in the water — that is where a trident earns
     * its place, and where closing the distance on foot is slowest.
     *
     * <p>The trident is conjured rather than taken from his hand: he never
     * loses it, never has to walk back for it, and the Loyalty on the item is
     * therefore decorative. The adapter disallows pickup so a thrown trident
     * cannot become loot on the floor and duplicate his kit.
     *
     * @return true if he threw, meaning he should hold position this tick
     */
    private boolean tryThrowTrident(Entity target, double dist) {
        if (!host.hasCapability(player, Rank.Capability.TRIDENT)) return false;
        if (dist > THROW_RANGE) return false;

        long now = System.currentTimeMillis();
        if (now - lastThrowMs < THROW_COOLDOWN_MS) return true;   // winding up; stand still
        lastThrowMs = now;

        Vec3 from = butler.pos().add(0, 1.4, 0);
        Vec3 toward = target.pos().add(0, 0.9, 0).subtract(from).normalize();

        butler.lookAt(target.pos());
        butler.swing();
        butler.throwTrident(from, toward, 2.5, 9.0);
        return true;
    }

    // ==================== ARCHERY ====================

    /** Blocks per tick at full draw, and the acceleration that pulls it down. */
    private static final double ARROW_SPEED   = 3.0;
    private static final double ARROW_GRAVITY = 0.05;
    /** How far he gives ground in one step when something gets inside his band. */
    private static final double RETREAT_STEP  = 4.0;

    /**
     * Loose an arrow at something out of reach.
     *
     * <p>Two things make this more than "spawn a projectile pointing at it".
     * Arrows take real time to arrive, so a target that is walking will not be
     * where it was when he let go; and they fall, so a flat shot lands short.
     * The aim point is therefore the target's position advanced by its own
     * velocity over the flight time, raised by the drop that flight will
     * accrue. Flight time depends on the distance to a point that depends on
     * flight time, so it is solved twice — one refinement is plenty at these
     * ranges and it is cheap.
     *
     * <p>Ammunition is conjured rather than drawn from a quiver, for the same
     * reason his tools are unbreakable: a butler who runs out mid-fight is a
     * chore. The adapter disallows pickup so his infinite arrows can never
     * become finite loot on the floor.
     *
     * @return true if he loosed or is mid-draw, meaning he should hold his ground
     */
    private boolean tryShoot(Entity mark, double dist) {
        if (dist > Armament.BOW.standOffMax()) return false;
        if (!butler.isSpawned()) return false;

        long now = System.currentTimeMillis();
        if (now - lastShotMs < shotCooldownMs) return true;   // still drawing; stand still

        Vec3 from = butler.pos().add(0, 1.4, 0);
        Vec3 centre = mark.eyePos().add(0, -0.4, 0);

        Vec3 drift = mark.velocity();
        Vec3 predicted = centre;
        double flight = 0;
        for (int pass = 0; pass < 2; pass++) {
            flight = from.distance(predicted) / ARROW_SPEED;
            predicted = centre.add(drift.scale(flight));
        }
        predicted = predicted.add(0, 0.5 * ARROW_GRAVITY * flight * flight, 0);

        Vec3 dir = predicted.subtract(from);
        if (dir.length() < 0.1) return false;
        dir = dir.normalize();

        // A butler does not shoot his employer in the back of the head.
        if (inLineOfFire(from, dir, dist)) return true;

        butler.lookAt(mark.pos());
        lastShotMs = now;

        Item bow = host.toolInHand(player);
        int power = bow.enchantLevel(Ids.ENCHANT_POWER);
        int punch = bow.enchantLevel(Ids.ENCHANT_PUNCH);
        boolean flame = bow.enchantLevel(Ids.ENCHANT_FLAME) > 0;

        // Vanilla resolves an arrow's damage as base x speed, so the base is
        // the damage he wants divided by the speed he throws it at. Power adds
        // 25% per level over a level-0 shot, as it does for a player.
        double scaled = arrowDamage / ARROW_SPEED;
        if (power > 0) scaled *= 1.0 + 0.25 * (power + 1);

        Vec3 muzzle = from.add(dir.scale(0.8));
        butler.shootArrow(muzzle, dir, ARROW_SPEED, scaled, punch, flame);
        return true;
    }

    /**
     * Is the player standing in the way? Measured as perpendicular distance
     * from the shot line, considered only between the bow and the target.
     */
    private boolean inLineOfFire(Vec3 from, Vec3 dir, double dist) {
        Vec3 toPlayer = player.eyePos().subtract(from);
        double along = toPlayer.x() * dir.x() + toPlayer.y() * dir.y() + toPlayer.z() * dir.z();
        if (along <= 0 || along >= dist) return false;
        double offAxis = toPlayer.subtract(dir.scale(along)).length();
        return offAxis < 1.2;
    }

    // ==================== GIVING GROUND ====================

    /**
     * Where he would step back to. Directly away from the target, on the level
     * — a retreat that walks him off a ledge is not a retreat.
     */
    private Vec3 retreatPoint(Vec3 npcLoc) {
        Vec3 away = npcLoc.subtract(target.pos());
        away = new Vec3(away.x(), 0, away.z());
        if (away.length() < 0.1) return null;
        return npcLoc.add(away.normalize().scale(RETREAT_STEP));
    }

    /**
     * Backing off is only an option if there is somewhere to back off to, and
     * if it does not take him off his leash — a bodyguard who retreats out of
     * the fight has stopped guarding.
     */
    private boolean canGiveGround(World world, Vec3 npcLoc, Vec3 anchor) {
        Vec3 back = retreatPoint(npcLoc);
        if (back == null) return false;
        if (anchor != null && back.distance(anchor) > leashRange) return false;

        BlockPos feet = back.block();
        return world.isPassable(feet)
                && world.isPassable(feet.above())
                && world.isSolid(feet.below());
    }

    private void giveGround(Vec3 npcLoc) {
        if (butler.isNavigating()) return;   // already moving; let him finish
        Vec3 back = retreatPoint(npcLoc);
        if (back == null) return;
        butler.navigateTo(back);
        navBusy = true;
    }

    /**
     * What his sword is actually worth.
     *
     * <p>Damage used to be a flat {@code defender.attack-damage} regardless of
     * what he was holding, which made every weapon upgrade — and the whole
     * Sharpness half of the service ladder — purely cosmetic in a fight. Now a
     * netherite sword hits like one.
     *
     * <p>Values follow vanilla: sword base by tier, plus Sharpness at
     * 1 + 0.5 per level beyond the first. The configured value is the floor,
     * so a bare-handed Jarvis is no weaker than he used to be.
     */
    private double weaponDamage(Item weapon) {
        if (weapon == null || weapon.isEmpty()) return attackDamage;

        double base = switch (weapon.id()) {
            case Ids.NETHERITE_SWORD  -> 8.0;
            case Ids.DIAMOND_SWORD    -> 7.0;
            case Ids.IRON_SWORD       -> 6.0;
            case "minecraft:stone_sword" -> 5.0;
            case "minecraft:golden_sword", "minecraft:wooden_sword" -> 4.0;
            case Ids.NETHERITE_AXE    -> 10.0;
            case Ids.DIAMOND_AXE      -> 9.0;
            case Ids.IRON_AXE         -> 9.0;
            case Ids.TRIDENT          -> 9.0;
            default                   -> attackDamage;
        };

        int sharpness = weapon.enchantLevel(Ids.ENCHANT_SHARPNESS);
        if (sharpness > 0) base += 1.0 + 0.5 * (sharpness - 1);

        // Never worse than the old flat value.
        return Math.max(base, attackDamage);
    }

    /** Fire Aspect is a real enchantment on his sword, so let it burn. */
    private void applyFireAspect(Item weapon, Entity target) {
        if (weapon == null) return;
        int level = weapon.enchantLevel(Ids.ENCHANT_FIRE_ASPECT);
        if (level > 0) target.setFireTicks(Math.max(target.fireTicks(), level * 80));
    }

    /** Called by the damage listener: someone hurt the player or Jarvis. */
    public void recordThreat(Entity damager) {
        if (damager != null && damager.kind() == EntityKind.HOSTILE) {
            threats.put(damager.id(), System.currentTimeMillis() + THREAT_MEMORY_MS);
        }
    }

    // ==================== MAIN LOOP ====================

    private void tick(Vec3 npcLoc) {
        threats.values().removeIf(expiry -> expiry < System.currentTimeMillis());

        World world = butler.world().orElse(null);
        if (world == null) return;
        WorldId here = world.id();

        Vec3 anchor;
        WorldId anchorWorld;
        switch (mode) {
            case SENTRY -> { anchor = post; anchorWorld = here; }
            case PATROL -> {
                if (patrolRoute.isEmpty()) { anchor = player.pos(); anchorWorld = player.world(); }
                else {
                    Site s = patrolRoute.get(patrolIndex % patrolRoute.size());
                    anchor = s.pos(); anchorWorld = s.world().id();
                }
            }
            default -> { anchor = player.pos(); anchorWorld = player.world(); }
        }
        if (!anchorWorld.equals(here)) {
            // Player changed worlds — bodyguard catches up, sentry stays
            if (mode == Mode.BODYGUARD) {
                butler.cancelNavigation();
                host.platform().world(player.world()).ifPresent(w ->
                        butler.teleport(w, player.pos(), player.look()));
            }
            return;
        }

        if (callouts && mode == Mode.BODYGUARD) {
            tickCallouts(world);
        }

        // ---- In combat ----
        if (target != null) {
            if (!target.isAlive() || !target.world().equals(here)) {
                // A melee kill credits itself the moment he lands the blow. An
                // arrow lands after he has already moved on, so a target that
                // dies while he is engaged is credited here instead --
                // otherwise archery, once it becomes his main weapon, would
                // silently stop earning him any standing at all.
                if (!target.isAlive() && target.world().equals(here)) {
                    host.credit(player, ServiceRecord.Discipline.COMBAT, 1);
                    host.sayQuiet(player, "Threat neutralised.");
                }
                disengage();
            } else if (target.pos().distance(anchor) > leashRange + 6) {
                // Target fled beyond the leash — let it go, return to post
                disengage();
            } else {
                fight(world, npcLoc, anchor);
                return;
            }
        }

        // ---- Pick a new target ----
        if (stance != Stance.PASSIVE) {
            Entity picked = selectTarget(anchor);
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
            anchor = patrolRoute.get(patrolIndex).pos();
        }

        double distToAnchor = npcLoc.distance(anchor);
        if (returning && distToAnchor <= 3.0) {
            returning = false;
            butler.cancelNavigation();
        }
        if (distToAnchor > (mode == Mode.BODYGUARD ? 4.0 : mode == Mode.PATROL ? 1.5 : 3.0)) {
            if (!butler.isNavigating()) {
                if (mode == Mode.BODYGUARD && distToAnchor > 30) {
                    butler.teleport(player.pos(), player.look());
                } else {
                    butler.navigateTo(anchor);
                }
            }
        } else if (butler.isNavigating() && distToAnchor <= 2.0) {
            butler.cancelNavigation();
        }
    }

    // ==================== COMBAT ====================

    /**
     * One tick of a fight.
     *
     * <p>This used to be a distance check with the trident bolted onto it. It
     * is now two steps: ask the doctrine what to do, then do that. Adding a
     * weapon is a case in {@link WeaponDoctrine}, not another branch here.
     */
    private void fight(World world, Vec3 npcLoc, Vec3 anchor) {
        double dist = npcLoc.distance(target.pos());

        Engagement plan = WeaponDoctrine.choose(assess(world, npcLoc, dist, anchor));
        host.drawWeapon(player, plan.weapon().kind());

        switch (plan.tactic()) {
            case STRIKE   -> strike(world, npcLoc);
            case CLOSE    -> closeOn();
            case LOOSE    -> {
                butler.cancelNavigation();
                navBusy = false;
                butler.lookAt(target.pos());
                boolean loosed = plan.weapon() == Armament.TRIDENT
                        ? tryThrowTrident(target, dist)
                        : tryShoot(target, dist);
                if (!loosed) closeOn();          // could not shoot; do it the old way
            }
            case WITHDRAW -> {
                butler.lookAt(target.pos());
                tryShoot(target, dist);          // a fighting retreat, not a rout
                giveGround(npcLoc);
            }
        }
    }

    /** Read off everything the weapon choice depends on. */
    private WeaponDoctrine.Situation assess(World world, Vec3 npcLoc, double dist, Vec3 anchor) {
        boolean bow = archery && host.hasCapability(player, Rank.Capability.ARCHERY);
        boolean trident = host.hasCapability(player, Rank.Capability.TRIDENT);

        // An arrow will not go round a corner. Without sight of the target a
        // bow is worse than useless -- he would stand at range plinking a wall.
        boolean sight = butler.hasLineOfSight(target);

        return new WeaponDoctrine.Situation(dist, host.isSubmerged(player), bow, trident,
                sight, target.isCreeper(), canGiveGround(world, npcLoc, anchor));
    }

    private void closeOn() {
        if (!navBusy || !butler.isNavigating()) {
            butler.navigateTo(target, true);
            navBusy = true;
        }
    }

    private void strike(World world, Vec3 npcLoc) {
        butler.cancelNavigation();
        navBusy = false;
        butler.lookAt(target.pos());

        long now = System.currentTimeMillis();
        if (now - lastAttackMs >= attackCooldownMs) {
            lastAttackMs = now;
            butler.swing();
            Item weapon = host.toolInHand(player);
            butler.attack(target, weaponDamage(weapon));
            applyFireAspect(weapon, target);
            world.sound(npcLoc, Ids.SOUND_ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
            creditIfDead();
        }
    }

    private void creditIfDead() {
        if (target != null && !target.isAlive()) {
            host.credit(player, ServiceRecord.Discipline.COMBAT, 1);
            host.sayQuiet(player, "Threat neutralised.");
            disengage();
        }
    }

    private void disengage() {
        target = null;
        navBusy = false;
        returning = true;
        butler.cancelNavigation();
        // Put the bow away. He walks back to his post with a sword, as a
        // butler should, rather than carrying a drawn weapon around the house.
        host.syncWeaponToSurroundings(player);
    }

    /** Choose the most pressing hostile per stance. Creepers first — they explode. */
    private Entity selectTarget(Vec3 anchor) {
        if (!butler.isSpawned()) return null;

        Entity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity e : butler.nearbyEntities(engageRadius, engageRadius, engageRadius)) {
            if (e.kind() != EntityKind.HOSTILE) continue;
            if (!e.isAlive()) continue;
            if (e.pos().distance(anchor) > engageRadius) continue;

            boolean isThreat = threats.containsKey(e.id()) || e.isTargeting(player);

            if (stance == Stance.DEFENSIVE && !isThreat) continue;

            double score = e.pos().distance(anchor);
            if (e.isCreeper()) score -= 100;       // always deal with creepers first
            else if (isThreat) score -= 50;        // active attackers next

            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // ==================== CALLOUTS ====================

    /** "Creeper, behind you, sir." — warn about hostiles outside the player's view cone. */
    private void tickCallouts(World world) {
        long now = System.currentTimeMillis();
        calloutCooldowns.values().removeIf(t -> t < now);

        Vec3 d = player.look().direction();
        Vec3 look = new Vec3(d.x(), 0, d.z());
        if (look.length() < 0.1) return;
        look = look.normalize();

        Vec3 playerPos = player.pos();
        for (Entity e : world.nearby(playerPos, CALLOUT_RADIUS, 6, CALLOUT_RADIUS)) {
            if (e.kind() != EntityKind.HOSTILE) continue;
            if (!e.isAlive() || calloutCooldowns.containsKey(e.id())) continue;

            Vec3 toMob = e.pos().subtract(playerPos);
            toMob = new Vec3(toMob.x(), 0, toMob.z());
            if (toMob.length() < 0.1) continue;
            toMob = toMob.normalize();

            // Behind or well outside the view cone (dot < 0.2 ≈ more than ~78° off-axis)
            double dot = look.x() * toMob.x() + look.z() * toMob.z();
            if (dot < 0.2) {
                String name = Blocks.pretty(e.typeId());
                boolean urgent = e.isCreeper();
                if (urgent || e.pos().distance(playerPos) < 6) {
                    host.say(player, (urgent ? "Creeper, " : "A " + name + ", ")
                            + "behind you, sir" + (urgent ? "!" : "."));
                    player.sound(Ids.SOUND_BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
                    calloutCooldowns.put(e.id(), now + CALLOUT_COOLDOWN_MS);
                }
            }
        }
    }
}
