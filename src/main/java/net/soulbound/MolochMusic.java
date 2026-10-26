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
 * Moloch's battle music: war drums and a chanting choir that follow the fight. Each section is a looping track in the
 * server pack (moloch:fight_*), played to everyone near the arena on the music-disc volume slider (Jukebox/Note Blocks),
 * so it never fights the normal Minecraft music, which is silenced for them while it plays.
 * <p>
 * Sections change on the next bar line, so the drums never stumble; the collapse and the death cut in at once.
 */
final class MolochMusic {
    /** track -> [length in ticks, bar length in ticks] */
    private static final Map<String, double[]> TRACKS = Map.of(
            "rise", new double[]{266.667, 66.667},
            "march", new double[]{533.333, 66.667},
            "champions", new double[]{533.333, 66.667},
            "scream", new double[]{533.333, 66.667},
            "collapse", new double[]{266.667, 66.667},
            "frenzy", new double[]{533.333, 66.667});
    private static final int STEP = 2;

    private final Soulbound plugin;
    private final Map<UUID, String> playing = new HashMap<>();
    private final Map<UUID, Long> startedAt = new HashMap<>();
    private final Set<UUID> endPlayed = new HashSet<>();
    private BukkitTask task;
    private long tick;

    MolochMusic(Soulbound plugin) { this.plugin = plugin; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, STEP); }
    void stop() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) silence(p);
        playing.clear(); startedAt.clear();
    }

    private static String key(String track) { return "moloch:fight_" + track; }

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
        boolean on = plugin.getConfig().getBoolean("boss.music", true);
        String state = on ? plugin.boss().musicState() : null;
        Location c = plugin.boss().musicCentre();
        double radius = plugin.getConfig().getDouble("boss.music-radius", 96);
        float vol = (float) plugin.getConfig().getDouble("boss.music-volume", 1.0);

        Set<UUID> listening = new HashSet<>();
        if (state != null && c != null) {
            for (Player p : c.getWorld().getPlayers())
                if (p.getLocation().distanceSquared(c) <= radius * radius) listening.add(p.getUniqueId());
        }
        // anyone who left, or a fight that's over: stop their music (the closing sting is left to ring out)
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
            if (state.equals("end")) {                       // he's dying: the music collapses into one last blow
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
                boolean now = state.equals("collapse") || cur.equals("collapse") || cur.equals("rise");
                double intoBar = elapsed % t[1];
                if (now || intoBar < STEP || t[1] - intoBar <= STEP) { play(p, state, vol); continue; }
            }
            if (elapsed >= t[0] - 1) play(p, cur, vol);      // loop
        }
    }
}
