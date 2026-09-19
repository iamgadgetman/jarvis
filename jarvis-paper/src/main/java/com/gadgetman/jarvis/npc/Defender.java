package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.combat.Armament;
import com.gadgetman.jarvis.npc.combat.Engagement;
import com.gadgetman.jarvis.npc.combat.WeaponDoctrine;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
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
    private final INPCProvider provider;

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
    private final boolean archery;
    private final long shotCooldownMs;
    private final double arrowDamage;
    private long lastShotMs = 0;

    // Reach used to live here as a single constant shared by every weapon.
    // It now belongs to the weapon -- see Armament.reach().
    private static final long THREAT_MEMORY_MS = 30_000;
    private static final long CALLOUT_COOLDOWN_MS = 8_000;
    private static final double CALLOUT_RADIUS = 10.0;

    Defender(JarvisNPC host, Player player, Stance stance, Mode mode) {
        this.host = host;
        this.plugin = host.getPlugin();
        this.player = player;
        this.provider = host.getProvider();
        this.stance = stance;
        this.mode = mode;
        this.post = mode == Mode.SENTRY
                ? host.getCurrentLocation(player).getBlock().getLocation().add(0.5, 0, 0.5) : null;

        var cfg = plugin.getConfig();
        this.engageRadius = cfg.getDouble("defender.engage-radius", 12.0);
        this.leashRange = cfg.getDouble("defender.leash-range", 12.0);
        this.attackDamage = cfg.getDouble("defender.attack-damage", 7.0);
        this.attackCooldownMs = cfg.getLong("defender.attack-cooldown-ticks", 12L) * 50L;
        this.callouts = cfg.getBoolean("defender.callouts", true);
        this.archery = cfg.getBoolean("defender.archery.enabled", true);
        this.shotCooldownMs = cfg.getLong("defender.archery.draw-ticks", 24L) * 50L;
        this.arrowDamage = cfg.getDouble("defender.archery.arrow-damage", 6.0);
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
        host.applyNavigatorDefaults(player, null);
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

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!provider.isSpawned(player) || !player.isOnline()) {
                    cancel();
                    host.taskDone(player, this);
                    return;
                }
                Location npcLoc = host.getCurrentLocation(player);
                host.pickupNearbyItems(player, npcLoc);
                tick(npcLoc);
            }
        };
        task.runTaskTimer(plugin, 0L, 10L);
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
     * <p>The trident is spawned rather than taken from his hand: he never
     * loses it, never has to walk back for it, and the Loyalty on the item is
     * therefore decorative. Pickup is disallowed so a thrown trident cannot
     * become loot on the floor and duplicate his kit.
     *
     * @return true if he threw, meaning he should hold position this tick
     */
    private boolean tryThrowTrident(LivingEntity target, double dist) {
        var progression = host.getPlugin().getProgressionManager();
        if (progression == null
                || !progression.has(player, com.gadgetman.jarvis.progression.Rank.Capability.TRIDENT)) {
            return false;
        }
        if (dist > THROW_RANGE) return false;

        long now = System.currentTimeMillis();
        if (now - lastThrowMs < THROW_COOLDOWN_MS) return true;   // winding up; stand still
        lastThrowMs = now;

        Entity self = provider.getEntity(player);
        if (self == null) return false;

        Location from = self.getLocation().add(0, 1.4, 0);
        Vector toward = target.getLocation().add(0, 0.9, 0).toVector()
                .subtract(from.toVector()).normalize();

        provider.lookAt(player, target.getLocation());
        if (self instanceof LivingEntity le) le.swingMainHand();

        org.bukkit.entity.Trident spear =
                from.getWorld().spawn(from, org.bukkit.entity.Trident.class, t -> {
                    t.setShooter(self instanceof org.bukkit.projectiles.ProjectileSource src ? src : null);
                    t.setVelocity(toward.multiply(2.5));
                    t.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                    t.setDamage(9.0);
                });
        from.getWorld().playSound(from, Sound.ITEM_TRIDENT_THROW, 1f, 1f);

        // Never leave it lying in the world if it misses everything.
        final org.bukkit.entity.Trident thrown = spear;
        new BukkitRunnable() {
            @Override public void run() {
                if (thrown.isValid()) thrown.remove();
            }
        }.runTaskLater(host.getPlugin(), 200L);

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
     * chore. Pickup is disallowed so his infinite arrows can never become
     * finite loot on the floor.
     *
     * @return true if he loosed or is mid-draw, meaning he should hold his ground
     */
    private boolean tryShoot(LivingEntity mark, double dist) {
        if (dist > Armament.BOW.standOffMax()) return false;

        Entity self = provider.getEntity(player);
        if (self == null) return false;

        long now = System.currentTimeMillis();
        if (now - lastShotMs < shotCooldownMs) return true;   // still drawing; stand still

        Location from = self.getLocation().add(0, 1.4, 0);
        Location centre = mark.getEyeLocation().subtract(0, 0.4, 0);

        Vector drift = mark.getVelocity().clone();
        Location predicted = centre.clone();
        double flight = 0;
        for (int pass = 0; pass < 2; pass++) {
            flight = from.distance(predicted) / ARROW_SPEED;
            predicted = centre.clone().add(drift.clone().multiply(flight));
        }
        predicted.add(0, 0.5 * ARROW_GRAVITY * flight * flight, 0);

        Vector dir = predicted.toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 0.01) return false;
        dir.normalize();

        // A butler does not shoot his employer in the back of the head.
        if (inLineOfFire(from, dir, dist)) return true;

        provider.lookAt(player, mark.getLocation());
        lastShotMs = now;

        ItemStack bow = host.getToolInHand(player);
        int power = bow == null ? 0 : bow.getEnchantmentLevel(Enchantment.POWER);
        int punch = bow == null ? 0 : bow.getEnchantmentLevel(Enchantment.PUNCH);
        boolean flame = bow != null && bow.getEnchantmentLevel(Enchantment.FLAME) > 0;

        // Vanilla resolves an arrow's damage as base x speed, so the base is
        // the damage he wants divided by the speed he throws it at. Power adds
        // 25% per level over a level-0 shot, as it does for a player.
        double scaled = arrowDamage / ARROW_SPEED;
        if (power > 0) scaled *= 1.0 + 0.25 * (power + 1);
        final double base = scaled;

        Location muzzle = from.clone().add(dir.clone().multiply(0.8));
        org.bukkit.entity.Arrow arrow = muzzle.getWorld().spawn(
                muzzle, org.bukkit.entity.Arrow.class, a -> {
                    a.setShooter(self instanceof org.bukkit.projectiles.ProjectileSource src ? src : null);
                    a.setVelocity(dir.clone().multiply(ARROW_SPEED));
                    a.setDamage(base);
                    a.setKnockbackStrength(punch);
                    a.setCritical(true);
                    a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                    if (flame) a.setFireTicks(100);
                });
        muzzle.getWorld().playSound(muzzle, Sound.ENTITY_ARROW_SHOOT, 1f, 1f);

        // Never leave a shaft lying in the world if it misses everything.
        new BukkitRunnable() {
            @Override public void run() {
                if (arrow.isValid()) arrow.remove();
            }
        }.runTaskLater(host.getPlugin(), 200L);

        return true;
    }

    /**
     * Is the player standing in the way? Measured as perpendicular distance
     * from the shot line, considered only between the bow and the target.
     */
    private boolean inLineOfFire(Location from, Vector dir, double dist) {
        Vector toPlayer = player.getEyeLocation().toVector().subtract(from.toVector());
        double along = toPlayer.dot(dir);
        if (along <= 0 || along >= dist) return false;
        double offAxis = toPlayer.clone().subtract(dir.clone().multiply(along)).length();
        return offAxis < 1.2;
    }

    // ==================== GIVING GROUND ====================

    /**
     * Where he would step back to. Directly away from the target, on the level
     * — a retreat that walks him off a ledge is not a retreat.
     */
    private Location retreatPoint(Location npcLoc) {
        Vector away = npcLoc.toVector().subtract(target.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) return null;
        return npcLoc.clone().add(away.normalize().multiply(RETREAT_STEP));
    }

    /**
     * Backing off is only an option if there is somewhere to back off to, and
     * if it does not take him off his leash — a bodyguard who retreats out of
     * the fight has stopped guarding.
     */
    private boolean canGiveGround(Location npcLoc, Location anchor) {
        Location back = retreatPoint(npcLoc);
        if (back == null) return false;
        if (anchor != null && anchor.getWorld() == back.getWorld()
                && back.distance(anchor) > leashRange) return false;

        Block feet = back.getBlock();
        return feet.isPassable()
                && feet.getRelative(0, 1, 0).isPassable()
                && feet.getRelative(0, -1, 0).getType().isSolid();
    }

    private void giveGround(Location npcLoc) {
        if (provider.isNavigating(player)) return;   // already moving; let him finish
        Location back = retreatPoint(npcLoc);
        if (back == null) return;
        provider.navigateTo(player, back);
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
    private double weaponDamage(ItemStack weapon) {
        if (weapon == null || weapon.getType() == Material.AIR) return attackDamage;

        double base = switch (weapon.getType()) {
            case NETHERITE_SWORD  -> 8.0;
            case DIAMOND_SWORD    -> 7.0;
            case IRON_SWORD       -> 6.0;
            case STONE_SWORD      -> 5.0;
            case GOLDEN_SWORD, WOODEN_SWORD -> 4.0;
            case NETHERITE_AXE    -> 10.0;
            case DIAMOND_AXE      -> 9.0;
            case IRON_AXE         -> 9.0;
            case TRIDENT          -> 9.0;
            default               -> attackDamage;
        };

        int sharpness = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness > 0) base += 1.0 + 0.5 * (sharpness - 1);

        // Never worse than the old flat value.
        return Math.max(base, attackDamage);
    }

    /** Fire Aspect is a real enchantment on his sword, so let it burn. */
    private void applyFireAspect(ItemStack weapon, LivingEntity target) {
        if (weapon == null) return;
        int level = weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        if (level > 0) target.setFireTicks(Math.max(target.getFireTicks(), level * 80));
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
                provider.cancelNavigation(player);
                provider.teleport(player, player.getLocation());
            }
            return;
        }

        if (callouts && mode == Mode.BODYGUARD) {
            tickCallouts();
        }

        // ---- In combat ----
        if (target != null) {
            if (!target.isValid() || target.isDead() || target.getWorld() != npcLoc.getWorld()) {
                // A melee kill credits itself the moment he lands the blow. An
                // arrow lands after he has already moved on, so a target that
                // dies while he is engaged is credited here instead --
                // otherwise archery, once it becomes his main weapon, would
                // silently stop earning him any standing at all.
                if (target.isDead()) {
                    host.credit(player,
                            com.gadgetman.jarvis.progression.ServiceRecord.Discipline.COMBAT, 1);
                    host.sayQuiet(player, "Threat neutralised.");
                }
                disengage();
            } else if (target.getLocation().distance(anchor) > leashRange + 6) {
                // Target fled beyond the leash — let it go, return to post
                disengage();
            } else {
                fight(npcLoc, anchor);
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
            provider.cancelNavigation(player);
        }
        if (distToAnchor > (mode == Mode.BODYGUARD ? 4.0 : mode == Mode.PATROL ? 1.5 : 3.0)) {
            if (!provider.isNavigating(player)) {
                if (mode == Mode.BODYGUARD && distToAnchor > 30) {
                    provider.teleport(player, player.getLocation());
                } else {
                    provider.navigateTo(player, anchor);
                }
            }
        } else if (provider.isNavigating(player) && distToAnchor <= 2.0) {
            provider.cancelNavigation(player);
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
    private void fight(Location npcLoc, Location anchor) {
        double dist = npcLoc.distance(target.getLocation());

        Engagement plan = WeaponDoctrine.choose(assess(npcLoc, dist, anchor));
        host.drawWeapon(player, plan.weapon().kind());

        switch (plan.tactic()) {
            case STRIKE   -> strike(npcLoc);
            case CLOSE    -> closeOn();
            case LOOSE    -> {
                provider.cancelNavigation(player);
                navBusy = false;
                provider.lookAt(player, target.getLocation());
                boolean loosed = plan.weapon() == Armament.TRIDENT
                        ? tryThrowTrident(target, dist)
                        : tryShoot(target, dist);
                if (!loosed) closeOn();          // could not shoot; do it the old way
            }
            case WITHDRAW -> {
                provider.lookAt(player, target.getLocation());
                tryShoot(target, dist);          // a fighting retreat, not a rout
                giveGround(npcLoc);
            }
        }
    }

    /** Read off everything the weapon choice depends on. */
    private WeaponDoctrine.Situation assess(Location npcLoc, double dist, Location anchor) {
        var progression = host.getPlugin().getProgressionManager();
        boolean bow = archery && progression != null
                && progression.has(player, com.gadgetman.jarvis.progression.Rank.Capability.ARCHERY);
        boolean trident = progression != null
                && progression.has(player, com.gadgetman.jarvis.progression.Rank.Capability.TRIDENT);

        // An arrow will not go round a corner. Without sight of the target a
        // bow is worse than useless -- he would stand at range plinking a wall.
        boolean sight = provider.getEntity(player) instanceof LivingEntity le
                && le.hasLineOfSight(target);

        return new WeaponDoctrine.Situation(dist, host.isSubmerged(player), bow, trident,
                sight, target instanceof Creeper, canGiveGround(npcLoc, anchor));
    }

    private void closeOn() {
        if (!navBusy || !provider.isNavigating(player)) {
            provider.navigateTo(player, target, true);
            navBusy = true;
        }
    }

    private void strike(Location npcLoc) {
        provider.cancelNavigation(player);
        navBusy = false;
        provider.lookAt(player, target.getLocation());

        long now = System.currentTimeMillis();
        if (now - lastAttackMs >= attackCooldownMs) {
            lastAttackMs = now;
            if (provider.getEntity(player) instanceof LivingEntity le) {
                le.swingMainHand();
            }
            ItemStack weapon = host.getToolInHand(player);
            target.damage(weaponDamage(weapon), provider.getEntity(player));
            applyFireAspect(weapon, target);
            npcLoc.getWorld().playSound(npcLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
            creditIfDead();
        }
    }

    private void creditIfDead() {
        if (target != null && (target.isDead() || !target.isValid())) {
            host.credit(player, com.gadgetman.jarvis.progression.ServiceRecord.Discipline.COMBAT, 1);
            host.sayQuiet(player, "Threat neutralised.");
            disengage();
        }
    }

    private void disengage() {
        target = null;
        navBusy = false;
        returning = true;
        provider.cancelNavigation(player);
        // Put the bow away. He walks back to his post with a sword, as a
        // butler should, rather than carrying a drawn weapon around the house.
        host.syncWeaponToSurroundings(player);
    }

    /** Choose the most pressing hostile per stance. Creepers first — they explode. */
    private LivingEntity selectTarget(Location anchor, Location npcLoc) {
        if (provider.getEntity(player) == null) return null;

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity e : provider.getEntity(player).getNearbyEntities(engageRadius, engageRadius, engageRadius)) {
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
