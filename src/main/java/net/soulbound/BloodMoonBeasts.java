package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Under a blood moon even the livestock turn on you: cows, pigs, sheep, chickens, goats and rabbits hunt players down. */
final class BloodMoonBeasts {
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(200, 10, 20), 0.8f);
    private final Soulbound plugin;
    private BukkitTask task;
    private final Map<UUID, Long> lastBite = new HashMap<>();
    private boolean on, babies;
    private double range, damage, speed;
    private int tick;

    BloodMoonBeasts(Soulbound plugin) { this.plugin = plugin; loadSettings(); }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        on = c.getBoolean("boss.blood-moon-angry-animals", true);
        range = Math.max(4, c.getDouble("boss.angry-animal-range", 16));
        damage = Math.max(0, c.getDouble("boss.angry-animal-damage", 3));
        speed = Math.max(0.5, c.getDouble("boss.angry-animal-speed", 1.5));
        babies = c.getBoolean("boss.angry-animal-babies", false);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 5L); }
    void stop() { if (task != null) task.cancel(); lastBite.clear(); }

    private static boolean livestock(Entity e) {
        return e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken || e instanceof Goat || e instanceof Rabbit;
    }

    private void tick() {
        if (!on || !plugin.boss().bloodMoon()) { if (!lastBite.isEmpty()) lastBite.clear(); return; }
        tick++;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)) continue;
            if (!plugin.boss().bloodMoonWorld(p.getWorld())) continue;
            for (Entity e : p.getNearbyEntities(range, range / 2, range)) {
                if (!livestock(e) || !(e instanceof Mob m) || m.isDead()) continue;
                if (!babies && e instanceof Ageable a && !a.isAdult()) continue;
                if (m.isLeashed() || m.isInsideVehicle()) continue;
                double d2 = m.getLocation().distanceSquared(p.getLocation());
                // only chase the nearest player: skip if someone else is clearly closer
                if (tick % 2 == 0) m.getPathfinder().moveTo(p, speed);
                m.lookAt(p);
                if (tick % 4 == 0) m.getWorld().spawnParticle(Particle.DUST, m.getEyeLocation(), 2, 0.12, 0.05, 0.12, 0, RED);
                if (d2 < 2.4 * 2.4 && Math.abs(m.getLocation().getY() - p.getLocation().getY()) < 1.6) {
                    Long last = lastBite.get(m.getUniqueId());
                    if (last != null && now - last < 1000) continue;
                    lastBite.put(m.getUniqueId(), now);
                    double dmg = (e instanceof Chicken || e instanceof Rabbit) ? damage / 2 : damage;
                    p.damage(dmg, m);
                    Vector kb = p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
                    if (kb.lengthSquared() > 0.001) p.setVelocity(p.getVelocity().add(kb.normalize().multiply(0.35).setY(0.25)));
                    m.getWorld().playSound(m.getLocation(), angrySound(e), 1f, 0.5f);
                }
            }
        }
        if (tick % 200 == 0) lastBite.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
    }

    private static String angrySound(Entity e) {
        if (e instanceof Cow) return "minecraft:entity.cow.hurt";
        if (e instanceof Pig) return "minecraft:entity.pig.hurt";
        if (e instanceof Sheep) return "minecraft:entity.sheep.hurt";
        if (e instanceof Chicken) return "minecraft:entity.chicken.hurt";
        if (e instanceof Goat) return "minecraft:entity.goat.screaming.ram_impact";
        return "minecraft:entity.rabbit.attack";
    }
}
