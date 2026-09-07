package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.npc.provider.INPCProvider;
import org.bukkit.Location;
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

    private static final double ATTACK_REACH = 2.7;
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

    private void fight(Location npcLoc) {
        double dist = npcLoc.distance(target.getLocation());

        if (dist > ATTACK_REACH) {
            // Underwater only: swimming to a guardian is slow and he is a
            // sitting duck doing it. On land he closes and uses the sword.
            if (host.isSubmerged(player) && tryThrowTrident(target, dist)) return;
            if (!navBusy || !provider.isNavigating(player)) {
                provider.navigateTo(player, target, true);
                navBusy = true;
            }
            return;
        }

        provider.cancelNavigation(player);
        navBusy = false;
        provider.lookAt(player, target.getLocation());
        host.syncWeaponToSurroundings(player);

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

            if (target.isDead() || !target.isValid()) {
                host.credit(player, com.gadgetman.jarvis.progression.ServiceRecord.Discipline.COMBAT, 1);
                host.sayQuiet(player, "Threat neutralised.");
                disengage();
            }
        }
    }

    private void disengage() {
        target = null;
        navBusy = false;
        returning = true;
        provider.cancelNavigation(player);
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
