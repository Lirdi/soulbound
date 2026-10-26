package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * God's battle music: organ, choir, bells and timpani, following the Seven Days. Dawn (days 1-2), Creation (3-5),
 * Wrath (6), Rest (7, his naps), and a closing Amen when he falls. Played on the music-disc slider (Jukebox/Note
 * Blocks) to everyone near the main island; the vanilla music is silenced for them meanwhile. Sections change on
 * the next bar line (3 seconds at 80 BPM).
 */
final class GodMusic {
    /** track -> [length in ticks, bar length in ticks] */
    private static final Map<String, double[]> TRACKS = Map.of(
            "dawn", new double[]{480, 60},
            "creation", new double[]{480, 60},
            "wrath", new double[]{480, 60},
            "rest", new double[]{480, 60});
    private static final int STEP = 2;

    private final Soulbound plugin;
    private final GodManager god;
    private final Map<UUID, String> playing = new HashMap<>();
    private final Map<UUID, Long> startedAt = new HashMap<>();
    private final Set<UUID> endPlayed = new HashSet<>();
    private BukkitTask task;
    private long tick;

    GodMusic(Soulbound plugin, GodManager god) { this.plugin = plugin; this.god = god; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, STEP); }
    void stop() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) silence(p);
        playing.clear(); startedAt.clear();
    }

    private static String key(String track) { return "soulbound:god.fight_" + track; }

    private void silence(Player p) {
        String cur = playing.get(p.getUniqueId());
        if (cur != null) p.stopSound(key(cur), SoundCategory.RECORDS);
    }

    private void play(Player p, String track, float vol) {
        silence(p);
        p.stopSound(SoundCategory.MUSIC);
        p.playSound(p, key(track), SoundCategory.RECORDS, vol, 1f);
        playing.put(p.getUniqueId(), track);
        startedAt.put(p.getUniqueId(), tick);
    }

    private void tick() {
        tick += STEP;
        boolean on = plugin.getConfig().getBoolean("god.music", true);
        String state = on ? god.musicState() : null;
        Location c = god.musicCentre();
        double radius = plugin.getConfig().getDouble("god.music-radius", 220);
        float vol = (float) plugin.getConfig().getDouble("god.music-volume", 1.0);

        Set<UUID> listening = new HashSet<>();
        if (state != null && c != null) {
            for (Player p : c.getWorld().getPlayers()) {
                Location l = p.getLocation();
                double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
                if (dx * dx + dz * dz <= radius * radius) listening.add(p.getUniqueId());
            }
        }
        for (UUID id : new HashSet<>(playing.keySet())) {
            if (listening.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) silence(p);
            playing.remove(id); startedAt.remove(id);
        }
        if (state == null) { endPlayed.clear(); return; }

        for (UUID id : listening) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (tick % 20 == 0) p.stopSound(SoundCategory.MUSIC);   // the vanilla dragon music waits
            if (state.equals("end")) {
                if (endPlayed.add(id)) {
                    silence(p); playing.remove(id); startedAt.remove(id);
                    p.playSound(p, key("end"), SoundCategory.RECORDS, vol, 1f);
                }
                continue;
            }
            String cur = playing.get(id);
            if (cur == null) { play(p, state, vol); continue; }
            double[] t = TRACKS.get(cur);
            long elapsed = tick - startedAt.get(id);
            if (!cur.equals(state)) {
                double intoBar = elapsed % t[1];
                if (intoBar < STEP || t[1] - intoBar <= STEP) { play(p, state, vol); continue; }
            }
            if (elapsed >= t[0] - 1) play(p, cur, vol);
        }
    }
}
