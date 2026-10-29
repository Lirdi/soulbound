package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Under a blood moon even the livestock turn on you: cows, pigs, sheep, chickens, goats, rabbits and horses hunt
 * players down. It's held at midnight for as long as the moon lasts, and nobody can sleep through it.
 */
final class BloodMoonBeasts implements Listener {
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(200, 10, 20), 0.8f);
    private final Soulbound plugin;
    private BukkitTask task;
    private final Map<UUID, Long> lastBite = new HashMap<>();
    private boolean on, babies;
    private double range, damage, speed, reach;
    private boolean night;
    private int tick;

    BloodMoonBeasts(Soulbound plugin) { this.plugin = plugin; loadSettings(); }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        on = c.getBoolean("boss.blood-moon-angry-animals", true);
        range = Math.max(4, c.getDouble("boss.angry-animal-range", 10));
        reach = Math.max(0.2, c.getDouble("boss.angry-animal-reach", 0.8));
        night = c.getBoolean("boss.blood-moon-always-night", true);
        damage = Math.max(0, c.getDouble("boss.angry-animal-damage", 3));
        speed = Math.max(0.5, c.getDouble("boss.angry-animal-speed", 1.5));
        babies = c.getBoolean("boss.angry-animal-babies", false);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 5L); }
    void stop() { if (task != null) task.cancel(); lastBite.clear(); }

    private static boolean livestock(Entity e) {
        return e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken || e instanceof Goat || e instanceof Rabbit
                || (e instanceof AbstractHorse && !(e instanceof Llama));
    }

    private void tick() {
        if (!on || !plugin.boss().bloodMoon()) { if (!lastBite.isEmpty()) lastBite.clear(); return; }
        tick++;
        if (night && tick % 4 == 0) holdMidnight();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)) continue;
            if (!plugin.boss().bloodMoonWorld(p.getWorld())) continue;
            for (Entity e : p.getNearbyEntities(range, range / 2, range)) {
                if (!livestock(e) || !(e instanceof Mob m) || m.isDead()) continue;
                if (!babies && e instanceof Ageable a && !a.isAdult()) continue;
                if (m.isLeashed() || m.isInsideVehicle() || !m.getPassengers().isEmpty()) continue;
                double d2 = m.getLocation().distanceSquared(p.getLocation());
                // only chase the nearest player: skip if someone else is clearly closer
                if (tick % 2 == 0) m.getPathfinder().moveTo(p, speed);
                m.lookAt(p);
                if (tick % 4 == 0) m.getWorld().spawnParticle(Particle.DUST, m.getEyeLocation(), 2, 0.12, 0.05, 0.12, 0, RED);
                // reach is measured edge to edge, so a horse's long body doesn't let it bite from further away
                double gap = Math.sqrt(d2) - m.getWidth() / 2 - p.getWidth() / 2;
                if (gap < reach && Math.abs(m.getLocation().getY() - p.getLocation().getY()) < 1.6) {
                    Long last = lastBite.get(m.getUniqueId());
                    if (last != null && now - last < 1000) continue;
                    lastBite.put(m.getUniqueId(), now);
                    boolean horse = e instanceof AbstractHorse;
                    double dmg = (e instanceof Chicken || e instanceof Rabbit) ? damage / 2 : horse ? damage * 1.5 : damage;
                    p.damage(dmg, m);
                    Vector kb = p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
                    if (kb.lengthSquared() > 0.001) p.setVelocity(p.getVelocity().add(kb.normalize().multiply(horse ? 0.7 : 0.35).setY(horse ? 0.4 : 0.25)));
                    m.getWorld().playSound(m.getLocation(), angrySound(e), 1f, 0.5f);
                }
            }
        }
        if (tick % 200 == 0) lastBite.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        if (tick % 80 == 0) riders();
    }

    /**
     * Witches ride phantoms across a blood-moon sky: now and then one swoops in over a player out in the open
     * (the Overworld only; Moloch's realm has its own sky). A few at most around anyone.
     */
    private void riders() {
        double chance = plugin.getConfig().getDouble("boss.blood-moon-witch-riders.chance", 0.25);
        int max = plugin.getConfig().getInt("boss.blood-moon-witch-riders.max-per-player", 2);
        if (chance <= 0 || max <= 0) return;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL || !plugin.boss().bloodMoonWorld(w)) continue;
            if (p.isDead() || (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)) continue;
            if (w.getHighestBlockYAt(p.getLocation()) > p.getLocation().getBlockY() + 1) continue;   // under a roof: they can't see you
            int near = 0;
            for (Entity e : p.getNearbyEntities(64, 64, 64)) if (e.getScoreboardTags().contains("blood_moon_rider")) near++;
            if (near >= max || r.nextDouble() >= chance) continue;
            double a = r.nextDouble() * Math.PI * 2, d = 14 + r.nextDouble() * 10;
            org.bukkit.Location at = p.getLocation().add(Math.cos(a) * d, 18 + r.nextDouble() * 8, Math.sin(a) * d);
            if (at.getY() > w.getMaxHeight() - 4 || !at.getBlock().isPassable() || !at.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            org.bukkit.entity.Phantom ph = w.spawn(at, org.bukkit.entity.Phantom.class, x -> {
                x.setSize(3);
                x.addScoreboardTag("blood_moon_rider");
                x.setPersistent(false);
                x.setShouldBurnInDay(false);
            });
            org.bukkit.entity.Witch witch = w.spawn(at, org.bukkit.entity.Witch.class, x -> { x.addScoreboardTag("blood_moon_rider"); x.setPersistent(false); });
            ph.addPassenger(witch);
            ph.setTarget(p);
            witch.setTarget(p);
            w.playSound(at, "minecraft:entity.witch.celebrate", 2f, 0.6f);
            w.playSound(at, "minecraft:entity.phantom.swoop", 2f, 0.8f);
        }
    }

    /** Keep every blood-moon world (but his realm, which has its own sky) at midnight. */
    private void holdMidnight() {
        World realm = plugin.zones().world();
        for (World w : Bukkit.getWorlds()) {
            if (w.equals(realm) || !plugin.boss().bloodMoonWorld(w) || w.getEnvironment() != World.Environment.NORMAL) continue;
            long t = w.getTime();
            if (Math.abs(t - 18000) > 40) w.setTime(18000);
        }
    }

    @EventHandler
    public void onBed(PlayerBedEnterEvent e) {
        if (!on || !plugin.boss().bloodMoon() || !plugin.boss().bloodMoonWorld(e.getPlayer().getWorld())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("You can't sleep under a blood moon.", NamedTextColor.RED));
    }

    private static String angrySound(Entity e) {
        if (e instanceof Cow) return "minecraft:entity.cow.hurt";
        if (e instanceof Pig) return "minecraft:entity.pig.hurt";
        if (e instanceof Sheep) return "minecraft:entity.sheep.hurt";
        if (e instanceof Chicken) return "minecraft:entity.chicken.hurt";
        if (e instanceof Goat) return "minecraft:entity.goat.screaming.ram_impact";
        if (e instanceof AbstractHorse) return "minecraft:entity.horse.angry";
        return "minecraft:entity.rabbit.attack";
    }
}
