package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Moloch's Curse: Weakness and Mining Fatigue for a set time of play, with Slowness for the first minutes.
 * The clock only runs while the player is online, and milk or dying doesn't clear it (it's reapplied every second).
 */
final class CurseManager implements Listener {
    private final Soulbound plugin;
    private final Store store;
    private final Map<UUID, Integer> left = new HashMap<>();
    private final Map<UUID, Integer> served = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask task;
    private int slowSeconds, capSeconds;

    CurseManager(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        loadSettings();
        ConfigurationSection s = store.data.getConfigurationSection("curses");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                left.put(id, s.getInt(k + ".left"));
                served.put(id, s.getInt(k + ".served"));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        slowSeconds = c.getInt("curse.slowness-minutes", 10) * 60;
        capSeconds = c.getInt("curse.max-minutes", 180) * 60;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) showBar(p);
    }

    void stop() {
        if (task != null) task.cancel();
        persist();
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) { Player p = Bukkit.getPlayer(e.getKey()); if (p != null) p.hideBossBar(e.getValue()); }
        bars.clear();
    }

    /** Add minutes of curse to a player (stacks, up to the cap). */
    void curse(Player p, int minutes) {
        UUID id = p.getUniqueId();
        int now = left.getOrDefault(id, 0);
        if (now <= 0) served.put(id, 0);
        left.put(id, Math.min(capSeconds, now + minutes * 60));
        persist();
        showBar(p);
        apply(p);
    }

    void lift(Player p) {
        UUID id = p.getUniqueId();
        left.remove(id); served.remove(id);
        BossBar b = bars.remove(id);
        if (b != null) p.hideBossBar(b);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        persist();
    }

    int secondsLeft(Player p) { return left.getOrDefault(p.getUniqueId(), 0); }

    private void tickSecond() {
        boolean changed = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            int l = left.getOrDefault(id, 0);
            if (l <= 0) continue;
            l--; changed = true;
            served.merge(id, 1, Integer::sum);
            if (l <= 0) {
                lift(p);
                p.sendMessage(Component.text("Moloch's curse lifts from you.", NamedTextColor.GREEN));
                continue;
            }
            left.put(id, l);
            if (!p.isDead()) apply(p);
            BossBar b = bars.get(id);
            if (b != null) {
                b.name(Component.text("Moloch's Curse  ·  " + clock(l), NamedTextColor.DARK_RED));
                b.progress(Math.max(0f, Math.min(1f, (float) l / Math.max(1, l + served.getOrDefault(id, 0)))));
            }
        }
        if (changed && Bukkit.getCurrentTick() % 1200 == 0) persist();
    }

    private void apply(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 0, true, false, true));
        if (served.getOrDefault(p.getUniqueId(), 0) < slowSeconds)
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, true, false, true));
    }

    private void showBar(Player p) {
        int l = left.getOrDefault(p.getUniqueId(), 0);
        if (l <= 0 || bars.containsKey(p.getUniqueId())) return;
        BossBar b = BossBar.bossBar(Component.text("Moloch's Curse  ·  " + clock(l), NamedTextColor.DARK_RED), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        bars.put(p.getUniqueId(), b);
        p.showBossBar(b);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { showBar(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        BossBar b = bars.remove(e.getPlayer().getUniqueId());
        if (b != null) e.getPlayer().hideBossBar(b);
        persist();
    }

    private void persist() {
        store.set("curses", null);
        for (Map.Entry<UUID, Integer> e : left.entrySet()) {
            if (e.getValue() <= 0) continue;
            store.set("curses." + e.getKey() + ".left", e.getValue());
            store.set("curses." + e.getKey() + ".served", served.getOrDefault(e.getKey(), 0));
        }
        store.save();
    }

    static String clock(int s) {
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }
}
