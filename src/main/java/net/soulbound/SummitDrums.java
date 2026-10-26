package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drums at the top of the pyramid: anyone who climbs to the summit hears Moloch's drums beating from the fire pit,
 * looping for as long as they stay. The hungrier he is, the louder and quicker they beat.
 */
final class SummitDrums {
    private static final int DRUM_TICKS = 290;   // the drums sound is 14.5 seconds long

    private final Soulbound plugin;
    private final Zones zones;
    private final Map<UUID, Integer> nextAt = new HashMap<>();
    private BukkitTask task;
    private int tick;
    private boolean enabled;
    private double radius;

    SummitDrums(Soulbound plugin, Zones zones) {
        this.plugin = plugin;
        this.zones = zones;
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("moloch.summit-drums", true);
        radius = c.getDouble("moloch.summit-radius", 10);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 10L); }
    void stop() { if (task != null) task.cancel(); }

    /** On the summit: around the fire pit, from a few blocks below its rim to well above it. */
    private boolean onSummit(Location l) {
        return zones.inRealm(l) && Math.abs(l.getX() - 0.5) <= radius && Math.abs(l.getZ() - 0.5) <= radius
                && l.getY() >= zones.pitY + 6 && l.getY() <= zones.pitY + 24;
    }

    private void check() {
        tick += 10;
        if (!enabled) return;
        Location pit = zones.pitCentre();
        if (pit == null) return;
        if (plugin.boss() != null && plugin.boss().active()) return;   // the battle music takes over from the drums
        double hunger = plugin.hunger().value() / 100.0;
        float volume = (float) (0.7 + 0.8 * hunger);
        float pitch = (float) (0.9 + 0.2 * hunger);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!onSummit(p.getLocation())) { nextAt.remove(id); continue; }
            Integer due = nextAt.get(id);
            if (due != null && tick < due) continue;
            p.playSound(pit.clone().add(0, 12, 0), "moloch:drums", volume, pitch);   // from the fire, so they're loudest beside it
            nextAt.put(id, tick + Math.round(DRUM_TICKS / pitch));
        }
    }
}
