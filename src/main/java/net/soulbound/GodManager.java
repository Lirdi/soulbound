package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * God, in place of the ender dragon: a giant old man with a long grey beard, in white robes, flying (or standing)
 * where the dragon is. The resource pack makes the dragon itself invisible, gives it an old man's voice and calls it
 * God; this puts the figure in its place every tick, with a halo, and passes players' hits on his body to the dragon.
 * The dragon's own fight (crystals, perching, breath, the rays of light when it dies) carries on underneath.
 */
final class GodManager implements Listener {
    static final String TAG = "soulbound_god";
    private static final Particle.DustOptions HALO = new Particle.DustOptions(Color.fromRGB(255, 214, 90), 2.2f);

    private final Soulbound plugin;
    private final Map<UUID, UUID> godOf = new HashMap<>();   // dragon -> its God figure
    private final Map<UUID, Vector> heading = new HashMap<>();
    private final Map<UUID, Location> lastAt = new HashMap<>();
    private BukkitTask task;
    private int tick;

    private boolean enabled, flyingPose;
    private double scale, hitShare, flyBack, flyDown, standDown;

    GodManager(Soulbound plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("god.enabled", true);
        flyingPose = c.getBoolean("god.flying-pose", false);
        scale = Math.max(1, Math.min(16, c.getDouble("god.scale", 9)));
        hitShare = c.getDouble("god.body-hit-damage", 0.5);
        flyBack = c.getDouble("god.flying-offset-back", 0.45);
        flyDown = c.getDouble("god.flying-offset-down", 0.15);
        standDown = c.getDouble("god.standing-offset-down", 0.3);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 1L); }

    void stop() {
        if (task != null) task.cancel();
        for (UUID id : godOf.values()) { Entity g = Bukkit.getEntity(id); if (g != null) g.remove(); }
        godOf.clear();
    }

    private double height() { return 1.8 * scale; }

    private void tickAll() {
        tick++;
        if (!enabled) return;
        if (tick % 40 == 1) scan();
        for (Iterator<Map.Entry<UUID, UUID>> it = godOf.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, UUID> e = it.next();
            Entity d = Bukkit.getEntity(e.getKey()), g = Bukkit.getEntity(e.getValue());
            if (!(d instanceof EnderDragon dragon) || !dragon.isValid()) {   // the dragon is gone: God fades into light
                if (g != null) {
                    fx(Particle.END_ROD, g.getLocation().add(0, height() * 0.5, 0), 200, scale * 0.5, 0.15);
                    g.getWorld().playSound(g.getLocation(), "minecraft:block.beacon.deactivate", 5f, 0.6f);
                    g.remove();
                }
                heading.remove(e.getKey()); lastAt.remove(e.getKey());
                it.remove();
                continue;
            }
            if (g == null || !g.isValid()) { it.remove(); continue; }
            follow(dragon, g);
        }
    }

    /** Give every dragon a God, and clear away any God left without a dragon (after a restart, say). */
    private void scan() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (EnderDragon d : w.getEntitiesByClass(EnderDragon.class)) {
                UUID gid = godOf.get(d.getUniqueId());
                Entity g = gid == null ? null : Bukkit.getEntity(gid);
                if (g == null || !g.isValid()) spawnGod(d);
            }
            for (Entity g : w.getEntities()) {
                if (!g.getScoreboardTags().contains(TAG)) continue;
                if (!godOf.containsValue(g.getUniqueId())) g.remove();
            }
        }
    }

    private void spawnGod(EnderDragon d) {
        Location l = d.getLocation();
        Entity g = summon(l.clone().subtract(0, height() * 0.5, 0), TAG);
        if (g == null) {
            plugin.getLogger().warning("Couldn't create God for the dragon at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                    + ". Check the console just above this line for the summon error.");
            return;
        }
        g.setPersistent(false);
        godOf.put(d.getUniqueId(), g.getUniqueId());
        plugin.getLogger().info("God has appeared in " + l.getWorld().getName() + " (" + g.getType().getKey() + ").");
    }

    /** Summon a God figure at a spot, with the given tag. Null if the summon didn't work. */
    Entity summon(Location at, String tag) {
        String cmd = String.format(java.util.Locale.ROOT,
                "execute in %s run summon minecraft:mannequin %.2f %.2f %.2f {Tags:[\"%s\",\"%s_new\"],profile:{texture:\"soulbound:entity/god\",model:\"wide\"},"
                        + "immovable:1b,hide_description:1b,NoGravity:1b,Silent:1b,attributes:[{id:\"minecraft:scale\",base:%.2f},{id:\"minecraft:max_health\",base:1000}],Health:1000.0f}",
                at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(), tag, tag, scale);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        for (Entity e : at.getWorld().getNearbyEntities(at, 8, height() + 8, 8)) {
            if (!e.getScoreboardTags().contains(tag + "_new")) continue;
            e.removeScoreboardTag(tag + "_new");
            return e;
        }
        return null;
    }

    /** Admin test: a standing God right here, no dragon, to check the skin and size. */
    String test(Player p) {
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(scale * 1.5 + 3));
        at.setYaw(p.getLocation().getYaw() + 180);
        Entity g = summon(at, TAG + "_test");
        if (g == null) return "The summon failed. The server console shows why (look for a red error just before this).";
        g.setRotation(at.getYaw(), 0);
        return "God summoned in front of you (" + g.getType().getKey() + "). Remove him with /kill @e[tag=" + TAG + "_test]";
    }

    /** Keep God where the dragon is: flying face-forward through the sky, or standing upright when it perches or dies. */
    private void follow(EnderDragon d, Entity g) {
        Location dl = d.getLocation();
        UUID id = d.getUniqueId();
        Location prev = lastAt.put(id, dl.clone());
        Vector dir = heading.getOrDefault(id, new Vector(0, 0, 1));
        if (prev != null && prev.getWorld() == dl.getWorld()) {
            Vector v = dl.toVector().subtract(prev.toVector());
            if (v.lengthSquared() > 0.0025) dir = dir.multiply(0.85).add(v.normalize().multiply(0.15)).normalize();
        }
        heading.put(id, dir);
        EnderDragon.Phase ph = d.getPhase();
        boolean standing = !flyingPose || switch (ph) {
            case LAND_ON_PORTAL, BREATH_ATTACK, SEARCH_FOR_BREATH_ATTACK_TARGET, ROAR_BEFORE_ATTACK, HOVER, DYING -> true;
            default -> false;
        };
        double h = height();
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0, 0, 1);
        flat.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        Location to;
        float pitch;
        if (standing) {
            to = dl.clone().subtract(0, h * standDown, 0);
            pitch = 0;
        } else {
            to = dl.clone().subtract(dir.clone().multiply(h * flyBack)).subtract(0, h * flyDown, 0);
            pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.getY()))));
        }
        to.setYaw(yaw);
        to.setPitch(pitch);
        g.teleport(to);
        Pose want = standing ? Pose.STANDING : Pose.FALL_FLYING;
        if (g.getPose() != want) g.setPose(want, true);
        if (g instanceof LivingEntity le) le.setBodyYaw(yaw);

        // his halo, a ring of gold light above his head
        if (tick % 3 == 0) {
            Location head = standing ? to.clone().add(0, h * 1.02, 0)
                    : to.clone().add(dir.clone().multiply(h * 0.98)).add(0, h * 0.12, 0);
            double r = 0.32 * scale;
            for (int i = 0; i < 14; i++) {
                double a = i * Math.PI * 2 / 14 + tick * 0.05;
                g.getWorld().spawnParticle(Particle.DUST, head.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, HALO, true);
            }
        }
    }

    /** Hits on God's body are passed on to the dragon (at a share of their damage); nothing else touches him. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onGodHurt(EntityDamageEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(TAG)) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent ed)) return;
        Entity src = ed.getDamager();
        Player p = src instanceof Player pl ? pl : (src instanceof Projectile pr && pr.getShooter() instanceof Player sh ? sh : null);
        if (p == null) return;
        if (src instanceof Projectile) src.remove();
        EnderDragon d = dragonOf(e.getEntity().getUniqueId());
        if (d == null || !d.isValid() || d.getPhase() == EnderDragon.Phase.DYING || d.getHealth() <= 0) return;
        double amount = e.getDamage() * hitShare;
        if (d.getHealth() - amount <= 0) d.damage(d.getHealth() * 4 + 10, p);   // the killing blow goes through the dragon, so the kill counts
        else {
            d.setHealth(d.getHealth() - amount);
            Location at = e.getEntity().getLocation().add(0, height() * 0.5, 0);
            at.getWorld().playSound(at, "minecraft:entity.ender_dragon.hurt", 4f, 1f);
            fx(Particle.DAMAGE_INDICATOR, p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(2)), 4, 0.2, 0.1);
        }
    }

    private EnderDragon dragonOf(UUID god) {
        for (Map.Entry<UUID, UUID> e : godOf.entrySet())
            if (e.getValue().equals(god) && Bukkit.getEntity(e.getKey()) instanceof EnderDragon d) return d;
        return null;
    }

    private static void fx(Particle p, Location at, int n, double spread, double speed) {
        at.getWorld().spawnParticle(p, at, n, spread, spread, spread, speed, null, true);
    }
}
