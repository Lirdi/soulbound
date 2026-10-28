package net.soulbound;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Zephyrs: the winds of Heaven, breezes that roam the outer islands. Their wind charge hits harder than a breeze's and
 * lifts you off your feet (Levitation), and Heaven's own Slow Falling lets you down again - if there's an island under
 * you. They come and go like natural mobs: a couple per player at most, never near God's island or during His fight,
 * and gone once nobody is near.
 */
final class Zephyrs implements Listener {
    private final Soulbound plugin;
    private final NamespacedKey key;

    Zephyrs(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "zephyr"); }

    void start() { plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 220L, 100L); }

    boolean is(Entity e) { return e instanceof Breeze && e.getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    Breeze summon(Location at) {
        return at.getWorld().spawn(at, Breeze.class, b -> {
            double hp = Math.max(1, plugin.getConfig().getDouble("zephyrs.health", 30));
            var mh = b.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (mh != null) { mh.setBaseValue(hp); b.setHealth(hp); }
            b.setRemoveWhenFarAway(true);
            b.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Every 5 seconds: clear away Zephyrs nobody is near, then maybe call one more onto an island near each player. */
    private void tick() {
        FileConfiguration c = plugin.getConfig();
        boolean on = c.getBoolean("zephyrs.enabled", true);
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Breeze b : w.getEntitiesByClass(Breeze.class)) if (is(b) && (!on || nobodyNear(b.getLocation(), 96))) b.remove();
            if (!on || (plugin.god() != null && plugin.god().fighting())) continue;
            double minR = c.getDouble("zephyrs.min-distance-from-centre", 300);
            int max = c.getInt("zephyrs.max-per-player", 1);
            double chance = c.getDouble("zephyrs.chance", 0.08);
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                Location l = p.getLocation();
                if (l.getX() * l.getX() + l.getZ() * l.getZ() < minR * minR) continue;
                if (ThreadLocalRandom.current().nextDouble() >= chance) continue;
                int near = 0;
                for (Entity e : p.getNearbyEntities(64, 64, 64)) if (is(e)) near++;
                if (near >= max) continue;
                Location at = ground(l, minR);
                if (at != null) {
                    summon(at);
                    w.spawnParticle(Particle.GUST_EMITTER_SMALL, at.clone().add(0, 1, 0), 1);
                }
            }
        }
    }

    private static boolean nobodyNear(Location l, double r) {
        for (Player p : l.getWorld().getPlayers()) if (p.getGameMode() != GameMode.SPECTATOR && p.getLocation().distanceSquared(l) < r * r) return false;
        return true;
    }

    /** A spot on island ground 16-32 blocks away, with room to stand, out on the Heavens (never over the void). */
    private static Location ground(Location from, double minR) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        World w = from.getWorld();
        for (int tries = 0; tries < 10; tries++) {
            double a = r.nextDouble(Math.PI * 2), d = 16 + r.nextDouble(16);
            int x = (int) Math.floor(from.getX() + Math.cos(a) * d), z = (int) Math.floor(from.getZ() + Math.sin(a) * d);
            if ((double) x * x + (double) z * z < minR * minR) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z);
            if (y < w.getMinHeight() + 4 || Math.abs(y - from.getY()) > 24) continue;
            Block top = w.getBlockAt(x, y, z);
            if (!top.getType().isSolid() || top.getType() == Material.BEDROCK) continue;
            if (!w.getBlockAt(x, y + 1, z).isEmpty() || !w.getBlockAt(x, y + 2, z).isEmpty()) continue;
            boolean seen = false;
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(new Location(w, x, y, z)) < 12 * 12) { seen = true; break; }
            if (seen) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    /** A Zephyr's wind charge: harder than a breeze's, and it lifts you into the air. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGust(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getDamager() instanceof AbstractWindCharge wc)) return;
        if (!(wc.getShooter() instanceof Entity shooter) || !is(shooter)) return;
        FileConfiguration c = plugin.getConfig();
        e.setDamage(c.getDouble("zephyrs.damage", 5));
        int secs = c.getInt("zephyrs.levitation-seconds", 3), lvl = Math.max(1, c.getInt("zephyrs.levitation-level", 2));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, secs * 20, lvl - 1, false, true, true));
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.3, 0), 12, 0.3, 0.1, 0.3, 0.02);
        });
    }
}
