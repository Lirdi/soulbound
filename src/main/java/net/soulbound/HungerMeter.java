package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Objective;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Locale;

/**
 * Moloch's Hunger: 0 to 100, filled by sacrifices and offerings. When it's full, Moloch's awakening is booked
 * for the next set time slot (so everyone can be online), and announced.
 */
final class HungerMeter implements Listener {
    private final Soulbound plugin;
    private final Store store;
    private double value;
    private long awakenAt;
    private BukkitTask task, barTask;
    private int lastScore = Integer.MIN_VALUE;

    // the Hunger bar: always shown in his realm, and to everyone for a few seconds whenever it grows
    private BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private double shown = -1, gained;
    private long popupUntil, lastSound;
    private boolean barEnabled, barEverywhere;
    private int popupSeconds;

    // settings
    private double perSacrifice, perOfferPoint;
    private String objective;
    private ZoneId zone;
    private final List<Slot> slots = new ArrayList<>();
    private int minNoticeHours, delayMinutes;
    private boolean useSlots;

    private record Slot(DayOfWeek day, LocalTime time) { }

    HungerMeter(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        value = store.data.getDouble("hunger.value", 0);
        awakenAt = store.data.getLong("hunger.awaken-at", 0);
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        perSacrifice = c.getDouble("hunger.per-sacrifice", 40);
        perOfferPoint = c.getDouble("hunger.per-offering-point", 0.5);
        objective = c.getString("hunger.datapack-objective", "sacrifice.offer");
        try { zone = ZoneId.of(c.getString("hunger.timezone", "Europe/London")); } catch (Exception ex) { zone = ZoneId.of("Europe/London"); }
        minNoticeHours = c.getInt("hunger.min-notice-hours", 6);
        useSlots = c.getBoolean("hunger.use-time-slots", false);
        delayMinutes = Math.max(0, c.getInt("hunger.awaken-delay-minutes", 5));
        barEnabled = c.getBoolean("hunger.bar.enabled", true);
        barEverywhere = c.getBoolean("hunger.bar.always-for-everyone", false);
        popupSeconds = Math.max(2, c.getInt("hunger.bar.popup-seconds", 8));
        slots.clear();
        for (String s : c.getStringList("hunger.awakening-slots")) {
            try {
                String[] parts = s.trim().split("\\s+");
                slots.add(new Slot(DayOfWeek.valueOf(parts[0].toUpperCase(Locale.ROOT)), LocalTime.parse(parts[1])));
            } catch (Exception ex) { plugin.getLogger().warning("Bad awakening slot '" + s + "' (use like: SATURDAY 20:00)"); }
        }
        if (slots.isEmpty()) slots.add(new Slot(DayOfWeek.SATURDAY, LocalTime.of(20, 0)));
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 40L, 20L);
        barTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBar, 40L, 2L);
    }

    void stop() {
        if (task != null) task.cancel();
        if (barTask != null) barTask.cancel();
        hideAll();
        persist();
    }

    double value() { return value; }
    long awakenAt() { return awakenAt; }

    void addSacrifice() { add(perSacrifice); }

    void set(double v) {
        double before = value;
        value = Math.max(0, Math.min(100, v));
        if (value > before) popup(value - before);
        persist();
        if (value >= 100 && awakenAt == 0) book();
    }

    /** After a fight: back to sleep. */
    void reset() { value = 0; awakenAt = 0; persist(); }

    /** Book the awakening for right now (admin). */
    void awakenNow() { awakenAt = System.currentTimeMillis(); persist(); }

    void add(double amount) {
        if (amount <= 0 || plugin.boss().active()) return;
        double before = value;
        value = Math.min(100, value + amount);
        announceCrossings(before, value);
        popup(value - before);
        persist();
        if (value >= 100 && awakenAt == 0) book();
    }

    private void announceCrossings(double a, double b) {
        if (a < 25 && b >= 25) say("The idol's eyes flicker in the dark.", NamedTextColor.GOLD);
        if (a < 50 && b >= 50) say("Drums sound from inside the pyramid. Moloch grows hungry.", NamedTextColor.RED);
        if (a < 75 && b >= 75) say("The ground trembles around the temple. Moloch is close to waking.", NamedTextColor.DARK_RED);
    }

    private void book() {
        if (!useSlots) {   // he rises right away, after a short warning so people can get to the arena
            awakenAt = System.currentTimeMillis() + delayMinutes * 60_000L;
            persist();
            Bukkit.broadcast(Component.text("MOLOCH STIRS.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            say("His hunger is full. He rises in " + delayMinutes + " minute" + (delayMinutes == 1 ? "" : "s") + ". Get to the arena under the pyramid!", NamedTextColor.RED);
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), "moloch:awaken", 0.8f, 1f);
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(zone), earliest = now.plusHours(minNoticeHours), best = null;
        for (Slot s : slots) {
            ZonedDateTime t = earliest.with(s.time());
            while (t.getDayOfWeek() != s.day() || t.isBefore(earliest)) t = t.plusDays(1).with(s.time());
            if (best == null || t.isBefore(best)) best = t;
        }
        awakenAt = best.toInstant().toEpochMilli();
        persist();
        Bukkit.broadcast(Component.text("MOLOCH STIRS.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        say("He rises " + when(best) + ". Come to the arena under the pyramid.", NamedTextColor.RED);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), "moloch:awaken", 0.6f, 1f);
    }

    String whenText() {
        if (awakenAt == 0) return "not yet";
        if (!useSlots) return "in " + CurseManager.clock((int) Math.max(0, (awakenAt - System.currentTimeMillis()) / 1000));
        return when(Instant.ofEpochMilli(awakenAt).atZone(zone));
    }

    private String when(ZonedDateTime t) {
        return t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.UK) + " at " + t.format(DateTimeFormatter.ofPattern("h:mma", Locale.UK)).toLowerCase(Locale.ROOT)
                + " (" + zone.getId() + ")";
    }

    private void tickSecond() {
        pollDatapack();
        // the countdown to his rising
        if (awakenAt > 0 && !plugin.boss().active()) {
            long left = (awakenAt - System.currentTimeMillis()) / 1000;
            if (left == 180 || left == 60 || left == 30 || left == 10) {
                say("Moloch rises in " + (left >= 60 ? (left / 60) + " minute" + (left == 60 ? "" : "s") : left + " seconds") + "!", NamedTextColor.RED);
                for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), "moloch:drums", 0.5f, 1.1f);
            }
        }
        if (awakenAt > 0 && System.currentTimeMillis() >= awakenAt && !plugin.boss().active()) {
            if (plugin.boss().begin()) { awakenAt = 0; persist(); }
            else awakenAt = System.currentTimeMillis() + 60_000;   // realm not loaded yet: try again in a minute
        }
    }

    /** The data pack keeps a running total of gold and mob offerings; turn its increases into Hunger. */
    private void pollDatapack() {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(objective);
        if (o == null) return;
        int s = o.getScore("#total").getScore();
        if (lastScore == Integer.MIN_VALUE) { lastScore = s; return; }
        int delta = s >= lastScore ? s - lastScore : s + 25 - lastScore;   // the pack takes 25 off each time it blesses the realm
        lastScore = s;
        if (delta > 0 && delta < 200) add(delta * perOfferPoint);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (awakenAt > 0) e.getPlayer().sendMessage(Component.text("Moloch rises " + whenText() + ".", NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- the Hunger bar

    /** Show everyone the bar for a few seconds, filling up by this much. */
    private void popup(double amount) {
        if (amount <= 0) return;
        long now = System.currentTimeMillis();
        gained = now < popupUntil ? gained + amount : amount;
        popupUntil = now + popupSeconds * 1000L;
        if (amount >= 5 && now - lastSound > 4000) {   // a sacrifice, not just a nugget of gold: everyone hears it
            lastSound = now;
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), "moloch:furnace", 0.35f, 0.8f);
        }
    }

    private void tickBar() {
        if (!barEnabled || plugin.boss().active()) { hideAll(); return; }   // the fight has its own bar
        if (bar == null) bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        // fill smoothly towards the real value, so a sacrifice visibly pours in
        if (shown < 0) shown = value;
        double d = value - shown;
        shown = Math.abs(d) < 0.15 ? value : shown + Math.signum(d) * Math.max(0.15, Math.abs(d) * 0.12);
        bar.progress((float) Math.max(0, Math.min(1, shown / 100)));
        long now = System.currentTimeMillis();
        boolean popping = now < popupUntil;
        Component name;
        if (awakenAt > 0) {
            name = Component.text("MOLOCH STIRS  ·  he rises " + whenText(), NamedTextColor.DARK_RED, TextDecoration.BOLD);
            bar.color(BossBar.Color.RED);
        } else {
            String extra = popping && gained > 0 ? "  (+" + Math.round(gained) + ")" : "";
            name = Component.text("Moloch's Hunger  ·  " + Math.round(shown) + "%" + extra, popping ? NamedTextColor.RED : NamedTextColor.DARK_RED);
            bar.color(popping ? BossBar.Color.YELLOW : BossBar.Color.RED);
        }
        bar.name(name);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean see = barEverywhere || popping || awakenAt > 0 || plugin.zones().inRealm(p.getLocation());   // the countdown is for everyone
            if (see && viewers.add(p.getUniqueId())) p.showBossBar(bar);
            if (!see && viewers.remove(p.getUniqueId())) p.hideBossBar(bar);
        }
    }

    private void hideAll() {
        if (bar == null) return;
        for (UUID id : viewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(bar); }
        viewers.clear();
    }

    private void persist() {
        store.set("hunger.value", value);
        store.set("hunger.awaken-at", awakenAt);
        store.save();
    }

    private static void say(String msg, NamedTextColor col) { Bukkit.broadcast(Component.text(msg, col)); }
}
