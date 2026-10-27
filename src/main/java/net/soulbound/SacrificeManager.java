package net.soulbound;

import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sacrifice: a captive dragged into the summit fire pit is held there for a short ritual. If the captor isn't
 * stopped in time, the captive dies, Moloch is fed, the captor is rewarded and the victim comes back cursed.
 */
final class SacrificeManager implements Listener {
    private final Soulbound plugin;
    private final Store store;
    private final CaptureManager captures;
    private final CurseManager curses;
    private final HungerMeter hunger;
    private final Zones zones;
    private final Map<UUID, Ritual> rituals = new HashMap<>();
    private final Set<UUID> sacrificing = new HashSet<>();
    private final Map<UUID, Long> lastRefusal = new HashMap<>();
    private BukkitTask task;

    // settings
    private int ritualTicks, curseMinutes, victimCooldownHours, captorPerDay, serverPerDay, newPlayerHours;
    private List<String> captorCommands = new ArrayList<>();

    private static final class Ritual {
        final Capture cap; int ticksLeft; final BossBar bar;
        Ritual(Capture cap, int ticks, BossBar bar) { this.cap = cap; this.ticksLeft = ticks; this.bar = bar; }
    }

    SacrificeManager(Soulbound plugin, Store store, CaptureManager captures, CurseManager curses, HungerMeter hunger, Zones zones) {
        this.plugin = plugin; this.store = store; this.captures = captures; this.curses = curses; this.hunger = hunger; this.zones = zones;
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        ritualTicks = Math.max(20, c.getInt("sacrifice.ritual-seconds", 10) * 20);
        curseMinutes = c.getInt("curse.minutes", 45);
        victimCooldownHours = c.getInt("sacrifice.victim-cooldown-hours", 48);
        captorPerDay = c.getInt("sacrifice.per-captor-per-day", 1);
        serverPerDay = c.getInt("sacrifice.server-per-day", 3);
        newPlayerHours = c.getInt("sacrifice.new-player-hours", 5);
        captorCommands = c.getStringList("sacrifice.captor-reward-commands");
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }

    void stop() {
        if (task != null) task.cancel();
        for (Ritual r : new ArrayList<>(rituals.values())) cancel(r, null);
    }

    boolean inRitual(Player p) { return rituals.containsKey(p.getUniqueId()); }

    // ---------------------------------------------------------------- every tick

    private void tick() {
        for (Capture cap : captures.all()) {
            if (cap.ritual) continue;
            Player victim = Bukkit.getPlayer(cap.captive), captor = Bukkit.getPlayer(cap.captor);
            if (victim == null || captor == null || !zones.inPitFire(victim.getLocation())) continue;   // they have to fall into the fire
            String no = refusal(captor, victim);
            if (no != null) {
                long now = System.currentTimeMillis();
                if (now - lastRefusal.getOrDefault(captor.getUniqueId(), 0L) > 3000) {
                    lastRefusal.put(captor.getUniqueId(), now);
                    captor.sendActionBar(Component.text("Moloch refuses: " + no, NamedTextColor.RED));
                }
                captures.moveCaptive(victim, captor.getLocation());
                continue;
            }
            begin(cap, captor, victim);
        }
        for (Ritual r : new ArrayList<>(rituals.values())) tickRitual(r);
    }

    private void begin(Capture cap, Player captor, Player victim) {
        cap.ritual = true;
        BossBar bar = BossBar.bossBar(Component.text(victim.getName() + " is being offered to Moloch", NamedTextColor.DARK_RED), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        Ritual r = new Ritual(cap, ritualTicks, bar);
        rituals.put(victim.getUniqueId(), r);
        Location pit = zones.pitCentre();
        for (Player p : victim.getWorld().getPlayers()) if (p.getLocation().distanceSquared(pit) < 120 * 120) { p.showBossBar(bar); p.playSound(pit, "moloch:drums", 1f, 1.15f); }
        victim.showTitle(Title.title(Component.text("THE RITUAL", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("Moloch hungers. Pray someone stops it.", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
        Bukkit.broadcast(Component.text(captor.getName() + " is sacrificing " + victim.getName() + " at the temple fire!", NamedTextColor.DARK_RED));
    }

    private void tickRitual(Ritual r) {
        Player victim = Bukkit.getPlayer(r.cap.captive), captor = Bukkit.getPlayer(r.cap.captor);
        if (victim == null || captor == null || !captures.isActive(r.cap)) { cancel(r, victim); return; }
        Location pit = zones.pitCentre();
        if (pit == null) { cancel(r, victim); return; }
        Location at = pit.clone(); at.setYaw(victim.getLocation().getYaw()); at.setPitch(victim.getLocation().getPitch());
        if (victim.getLocation().distanceSquared(at) > 0.36) captures.moveCaptive(victim, at);
        victim.setVelocity(new Vector(0, 0, 0));
        if (r.ticksLeft % 4 == 0) victim.getWorld().spawnParticle(Particle.FLAME, pit.clone().add(0, 1, 0), 24, 0.6, 1.0, 0.6, 0.03);
        // the scream: started 4 seconds out, so its fire-roar lands exactly as the victim is taken
        if (r.ticksLeft == Math.min(80, ritualTicks)) {
            for (Player p : victim.getWorld().getPlayers()) if (p.getLocation().distanceSquared(pit) < 160 * 160) p.playSound(pit, "moloch:sacrifice_scream", 2.5f, 1f);
            victim.playSound(victim.getLocation(), "moloch:sacrifice_scream", 1f, 1f);
        }
        r.ticksLeft--;
        r.bar.progress(Math.max(0f, (float) r.ticksLeft / ritualTicks));
        if (r.ticksLeft <= 0) complete(r, captor, victim);
    }

    private void cancel(Ritual r, Player victim) {
        rituals.remove(r.cap.captive);
        r.cap.ritual = false;
        for (Player p : Bukkit.getOnlinePlayers()) { p.hideBossBar(r.bar); p.stopSound("moloch:sacrifice_scream"); }
        if (victim != null) {
            victim.showTitle(Title.title(Component.text("Saved", NamedTextColor.GREEN, TextDecoration.BOLD), Component.text("The ritual was broken.", NamedTextColor.GRAY)));
            Bukkit.broadcast(Component.text("The ritual was broken. " + victim.getName() + " escapes the fire.", NamedTextColor.GREEN));
        }
    }

    private void complete(Ritual r, Player captor, Player victim) {
        rituals.remove(r.cap.captive);
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(r.bar);
        record(captor, victim);
        captures.end(r.cap, CaptureManager.End.SACRIFICED);
        Location pit = zones.pitCentre();
        victim.getWorld().spawnParticle(Particle.LAVA, pit.clone().add(0, 1, 0), 40, 0.7, 0.7, 0.7, 0);
        for (Player p : victim.getWorld().getPlayers()) if (p.getLocation().distanceSquared(pit) < 160 * 160) p.playSound(pit, "moloch:furnace", 1.2f, 1f);
        // he screams as he feeds: the same scream that shakes his hall at half health
        org.bukkit.World w = pit.getWorld();
        Location head = pit.clone().add(0, 3, 0);
        w.playSound(head, "minecraft:entity.warden.roar", 4f, 0.55f);
        w.playSound(head, "minecraft:entity.ghast.scream", 4f, 0.5f);
        w.playSound(head, "minecraft:entity.ravager.roar", 4f, 0.6f);
        w.spawnParticle(Particle.SONIC_BOOM, head, 1);
        for (int ring = 1; ring <= 3; ring++) {
            final int rr = ring;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int i = 0; i < 24; i++) {
                    double a2 = i * Math.PI * 2 / 24, d = rr * 6;
                    w.spawnParticle(Particle.SONIC_BOOM, pit.clone().add(Math.cos(a2) * d, 4, Math.sin(a2) * d), 1);
                }
            }, rr * 3L);
        }
        int nausea = Math.max(1, plugin.getConfig().getInt("boss.scream-nausea-seconds", 11));
        int weak = Math.max(0, plugin.getConfig().getInt("boss.scream-weakness-seconds", 6));
        for (Player p : w.getPlayers()) {
            if (p.equals(victim) || p.getLocation().distanceSquared(pit) > 48 * 48) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nausea * 20, 0, false, false, true));
            if (weak > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weak * 20, 0, false, false, true));
            p.showTitle(Title.title(Component.text("MOLOCH FEEDS", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("the summit spins around you", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(800))));
        }
        sacrificing.add(victim.getUniqueId());
        victim.setHealth(0.0);
        curses.curse(victim, curseMinutes);
        hunger.addSacrifice();
        reward(captor);
        String firstPath = "sacrifice.first-done." + captor.getUniqueId();
        boolean first = !store.data.getBoolean(firstPath, false);
        store.set(firstPath, true);
        if (plugin.priest() != null) plugin.priest().onSacrifice(captor, victim.getName(), first);
        for (String cmd : captorCommands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", captor.getName()));
        captor.showTitle(Title.title(Component.text("Moloch accepts", NamedTextColor.GOLD, TextDecoration.BOLD), Component.text("Your offering is taken.", NamedTextColor.GRAY)));
        Bukkit.broadcast(Component.text(victim.getName() + " was sacrificed to Moloch by " + captor.getName() + ".", NamedTextColor.DARK_RED, TextDecoration.BOLD));
    }

    /** The advancement (from the Sacrifice data pack) and, by default the first time only, the Skull of Mictlan. */
    private void reward(Player captor) {
        grant(captor, "sacrifice:moloch/root");
        grant(captor, "sacrifice:moloch/offering");
        String mode = plugin.getConfig().getString("sacrifice.skull-reward", "first");
        String path = "sacrifice.skull-given." + captor.getUniqueId();
        if (mode.equalsIgnoreCase("never") || (mode.equalsIgnoreCase("first") && store.data.getBoolean(path, false))) return;
        store.set(path, true);
        org.bukkit.inventory.ItemStack skull = plugin.gear().skull(captor.getName());
        captor.getInventory().addItem(skull).values().forEach(left -> captor.getWorld().dropItem(captor.getLocation(), left));
        captor.sendMessage(Component.text("Moloch gives you the Skull of Mictlan.", NamedTextColor.DARK_AQUA));
    }

    private static void grant(Player p, String id) {
        org.bukkit.NamespacedKey k = org.bukkit.NamespacedKey.fromString(id);
        org.bukkit.advancement.Advancement a = k == null ? null : Bukkit.getAdvancement(k);
        if (a == null) return;
        org.bukkit.advancement.AdvancementProgress pr = p.getAdvancementProgress(a);
        for (String c : pr.getRemainingCriteria()) pr.awardCriteria(c);
    }

    // ---------------------------------------------------------------- limits

    private String refusal(Player captor, Player victim) {
        long now = System.currentTimeMillis();
        if (victim.getStatistic(Statistic.PLAY_ONE_MINUTE) < newPlayerHours * 72000L) return victim.getName() + " is too new to the world.";
        if (captor.getStatistic(Statistic.PLAY_ONE_MINUTE) < newPlayerHours * 72000L) return "you're too new to offer anyone.";
        long last = store.data.getLong("sacrifice.victims." + victim.getUniqueId(), 0);
        if (now - last < victimCooldownHours * 3600_000L) return victim.getName() + " was offered too recently.";
        if (count("sacrifice.captors." + captor.getUniqueId()) >= captorPerDay) return "you've offered enough today.";
        if (count("sacrifice.server") >= serverPerDay) return "the fire has eaten enough today.";
        return null;
    }

    /** An uncursed player is free to be offered again: forget when they were last offered. */
    void forgetVictim(UUID id) { store.set("sacrifice.victims." + id, null); }

    /** Admin reset: a player's victim cooldown and daily captor count, or (null) the whole server's daily limit too. */
    void resetLimits(UUID id) {
        if (id != null) { forgetVictim(id); store.set("sacrifice.captors." + id, null); return; }
        store.set("sacrifice.victims", null);
        store.set("sacrifice.captors", null);
        store.set("sacrifice.server", null);
    }

    private int count(String path) {
        String v = store.data.getString(path, "");
        String today = LocalDate.now().toString();
        if (!v.startsWith(today + ":")) return 0;
        try { return Integer.parseInt(v.substring(today.length() + 1)); } catch (NumberFormatException ex) { return 0; }
    }

    private void record(Player captor, Player victim) {
        String today = LocalDate.now().toString();
        store.set("sacrifice.victims." + victim.getUniqueId(), System.currentTimeMillis());
        store.set("sacrifice.last-offered-by." + captor.getUniqueId(), System.currentTimeMillis());   // God remembers
        store.set("sacrifice.captors." + captor.getUniqueId(), today + ":" + (count("sacrifice.captors." + captor.getUniqueId()) + 1));
        store.set("sacrifice.server", today + ":" + (count("sacrifice.server") + 1));
        store.save();
    }

    // ---------------------------------------------------------------- events

    /** Nothing hurts the offering during the ritual: Moloch takes them whole. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && rituals.containsKey(p.getUniqueId())) { e.setCancelled(true); p.setFireTicks(0); }
    }

    /** The sacrificed keep their things; the curse is the price. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (!sacrificing.remove(e.getEntity().getUniqueId())) return;
        e.deathMessage(Component.text(e.getEntity().getName() + " was given to the fire.", NamedTextColor.DARK_RED));
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
    }
}
