package net.soulbound;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cracks the player's screen: a full-screen glyph from the pack's soulbound:fx font, shown as a title.
 * Each hit within a few seconds of the last one cracks it further (three stages); the cracks spread over a few
 * ticks, hold, then fade away.
 */
public final class ScreenCrack {
    private static final Key FONT = Key.key("soulbound", "fx");
    private static final int STAGES = 3, FRAMES = 3;
    private final Soulbound plugin;
    private final Map<UUID, Integer> stage = new HashMap<>();
    private final Map<UUID, Long> last = new HashMap<>();
    private final Map<UUID, BukkitTask> anim = new HashMap<>();

    public ScreenCrack(Soulbound plugin) { this.plugin = plugin; }

    private static Component glyph(int stage, int frame) {
        char c = (char) (0xE100 + (stage - 1) * FRAMES + frame);
        return Component.text().content(String.valueOf(c)).font(FONT).shadowColor(ShadowColor.none()).build();
    }

    public void crack(Player p) {
        if (!plugin.getConfig().getBoolean("screen-crack.enabled", true)) return;
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long keep = (long) (plugin.getConfig().getDouble("screen-crack.stack-seconds", 6) * 1000);
        int s = (last.containsKey(id) && now - last.get(id) < keep) ? Math.min(STAGES, stage.getOrDefault(id, 0) + 1) : 1;
        stage.put(id, s); last.put(id, now);
        BukkitTask old = anim.remove(id);
        if (old != null) old.cancel();

        p.playSound(p.getLocation(), "minecraft:block.glass.break", 1.2f, s == STAGES ? 0.5f : 0.7f + 0.1f * s);
        if (s == STAGES) p.playSound(p.getLocation(), "minecraft:entity.zombie.break_wooden_door", 0.6f, 0.6f);
        final int st = s;
        final int hold = (int) (plugin.getConfig().getDouble("screen-crack.hold-seconds", 2.5) * 20);
        BukkitTask t = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int f = 0;
            @Override public void run() {
                if (!p.isOnline()) { stop(); return; }
                boolean lastFrame = f == FRAMES - 1;
                Title.Times times = lastFrame
                        ? Title.Times.times(Duration.ZERO, Duration.ofMillis(hold * 50L), Duration.ofMillis(1500))
                        : Title.Times.times(Duration.ZERO, Duration.ofMillis(300), Duration.ZERO);
                p.showTitle(Title.title(glyph(st, f), Component.empty(), times));
                if (lastFrame) stop(); else f++;
            }
            private void stop() { BukkitTask me = anim.remove(id); if (me != null) me.cancel(); }
        }, 0L, 2L);
        anim.put(id, t);
    }
}
