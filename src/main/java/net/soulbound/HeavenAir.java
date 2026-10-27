package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Heaven isn't built for bodies: out among the Seven Heavens (anywhere off God's main island), everyone floats
 * (Slow Falling) and the islands resist being dug (Mining Fatigue III). A visit is dreamy; living up there is
 * tiring. The main island, where everyone arrives and God holds court, is left alone.
 */
final class HeavenAir {
    private final Soulbound plugin;
    private final GodManager god;
    private BukkitTask task;

    HeavenAir(Soulbound plugin, GodManager god) { this.plugin = plugin; this.god = god; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L); }
    void stop() { if (task != null) task.cancel(); }

    private void tick() {
        var c = plugin.getConfig();
        if (!c.getBoolean("heaven-air.enabled", true)) return;
        boolean fight = god.fighting();
        int fatigue = c.getInt("heaven-air.mining-fatigue-level", 3);
        double r = c.getDouble("heaven-air.main-island-radius", 220), r2 = r * r;
        boolean floaty = c.getBoolean("heaven-air.slow-falling", true);
        float thin = (float) c.getDouble("heaven-air.thin-air-exhaustion", 0.0);   // per 2 seconds; 4 = one food point
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                if (fight) continue;
                double x = p.getLocation().getX(), z = p.getLocation().getZ();
                if (x * x + z * z <= r2) continue;   // God's island: no effects
                if (floaty) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 70, 0, true, false, true));
                if (fatigue > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 70, fatigue - 1, true, false, true));
                if (thin > 0) p.setExhaustion(p.getExhaustion() + thin);
            }
        }
    }
}
