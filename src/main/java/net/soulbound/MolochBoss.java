package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Moloch himself: a giant warden the plugin spawns on the arena dais when the Hunger meter's awakening comes round.
 * Everyone fights him together. Kill him in time and everyone who hurt him is rewarded;
 * fail and he goes back to sleep fed, and a blood moon hangs over his realm.
 */
final class MolochBoss implements Listener {
    static final String TAG = "moloch_boss", MINION = "moloch_minion";

    private final Soulbound plugin;
    private final Store store;
    private final Zones zones;
    private final Random rnd = new Random();
    private BukkitTask task;

    private boolean active;
    private Warden boss;
    private BossBar bar, moonBar;
    private final Set<UUID> barViewers = new HashSet<>(), moonViewers = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<UUID> minions = new HashSet<>();
    private double maxHp, savedHp;
    private long endsAt, bloodMoonUntil;
    private int phase, half, ringWindup = -1;

    // settings
    private double scale, baseHealth, perPlayer, boomCap, ringDamage, ringRadius, minDamage, leashRadius;
    private int fightMinutes, ringEvery, cultistEvery, cultistCount, maxMinions, bloodMoonHours;
    private boolean blockWardens;
    private List<String> participantCommands = new ArrayList<>(), topCommands = new ArrayList<>();

    MolochBoss(Soulbound plugin, Store store, Zones zones) {
        this.plugin = plugin; this.store = store; this.zones = zones;
        bloodMoonUntil = store.data.getLong("blood-moon-until", 0);
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        scale = c.getDouble("boss.scale", 3.0);
        baseHealth = c.getDouble("boss.base-health", 300);
        perPlayer = c.getDouble("boss.health-per-player", 150);
        fightMinutes = c.getInt("boss.fight-minutes", 30);
        boomCap = c.getDouble("boss.sonic-boom-max-damage", 8);
        ringDamage = c.getDouble("boss.fire-ring-damage", 6);
        ringRadius = c.getDouble("boss.fire-ring-radius", 8);
        ringEvery = Math.max(5, c.getInt("boss.fire-ring-every-seconds", 20));
        cultistEvery = Math.max(10, c.getInt("boss.cultists-every-seconds", 45));
        cultistCount = c.getInt("boss.cultists-per-wave", 3);
        maxMinions = c.getInt("boss.max-cultists", 8);
        leashRadius = c.getDouble("boss.leash-radius", 26);
        minDamage = c.getDouble("boss.reward-min-damage", 10);
        participantCommands = c.getStringList("boss.reward-commands");
        topCommands = c.getStringList("boss.top-damage-commands");
        bloodMoonHours = c.getInt("boss.blood-moon-hours", 24);
        blockWardens = c.getBoolean("boss.block-other-wardens", true);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHalf, 10L, 10L); }

    void stop() {
        if (task != null) task.cancel();
        if (active) {   // a restart mid-fight: tidy up, and wake him again as soon as the server's back
            cleanup();
            plugin.hunger().awakenNow();
        }
        hideAll(moonBar, moonViewers);
    }

    boolean active() { return active; }
    Warden boss() { return boss; }

    // ---------------------------------------------------------------- the awakening

    /** Start the fight. False if Moloch's realm isn't loaded. */
    boolean begin() {
        if (active) return true;
        World w = zones.world();
        Location c = zones.arenaCentre();
        if (w == null || c == null) return false;
        w.getChunkAt(c).addPluginChunkTicket(plugin);
        int fighters = Math.max(1, w.getPlayers().size());
        maxHp = Math.min(1024, baseHealth + perPlayer * fighters);
        savedHp = maxHp;
        damage.clear();
        phase = 1; half = 0; ringWindup = -1;
        endsAt = System.currentTimeMillis() + fightMinutes * 60_000L;
        active = true;
        bar = BossBar.bossBar(Component.text("Moloch", NamedTextColor.DARK_RED, TextDecoration.BOLD), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        spawnBoss(savedHp);
        w.spawnParticle(Particle.LAVA, c, 80, 3, 2, 3, 0);
        Bukkit.broadcast(Component.text("MOLOCH HAS AWOKEN.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("Everyone to the arena under the pyramid. You have " + fightMinutes + " minutes to bring him down.", NamedTextColor.RED));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), "moloch:awaken", 1f, 1f);
            if (zones.inRealm(p.getLocation())) p.showTitle(Title.title(Component.text("MOLOCH", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("has awoken", NamedTextColor.RED), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
        }
        return true;
    }

    private void spawnBoss(double health) {
        Location c = zones.arenaCentre();
        boss = c.getWorld().spawn(c, Warden.class, wd -> {
            set(wd, Attribute.SCALE, scale);
            set(wd, Attribute.MAX_HEALTH, maxHp);
            set(wd, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            wd.customName(Component.text("Moloch", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            wd.setCustomNameVisible(true);
            wd.setPersistent(false);
            wd.setRemoveWhenFarAway(false);
            wd.addScoreboardTag(TAG);
        });
        boss.setHealth(Math.max(1, Math.min(maxHp, health)));
    }

    private static void set(org.bukkit.entity.LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    // ---------------------------------------------------------------- every half second

    private void tickHalf() {
        tickBloodMoon();
        if (!active) return;
        half++;
        Location c = zones.arenaCentre();
        if (c == null) return;
        if (boss == null || !boss.isValid()) {
            if (boss != null && boss.isDead()) return;   // the death event finishes it
            spawnBoss(savedHp);                          // he dug away or was unloaded: he rises again where he was
            c.getWorld().spawnParticle(Particle.LAVA, c, 40, 2, 1, 2, 0);
        }
        savedHp = boss.getHealth();

        // keep him in his hall
        Location b = boss.getLocation();
        if (Math.max(Math.abs(b.getX() - c.getX()), Math.abs(b.getZ() - c.getZ())) > leashRadius || b.getY() < c.getY() - 4 || b.getY() > c.getY() + 30)
            boss.teleport(c);

        List<Player> fighters = fighters();
        if (!fighters.isEmpty()) {
            Player near = fighters.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).get();
            boss.setAnger(near, 100);   // always hunting, so he never digs back down
        }

        // the bar
        bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / maxHp)));
        long left = Math.max(0, endsAt - System.currentTimeMillis()) / 1000;
        bar.name(Component.text("Moloch  ·  " + CurseManager.clock((int) left), NamedTextColor.DARK_RED, TextDecoration.BOLD));
        showNear(bar, barViewers, c, 160);

        // phases
        double ratio = boss.getHealth() / maxHp;
        int now = ratio > 0.66 ? 1 : ratio > 0.33 ? 2 : 3;
        if (now > phase) {
            phase = now;
            sayNear(c, phase == 2 ? "The furnace in Moloch's belly roars open!" : "MOLOCH HUNGERS. Fire falls from the vault!", NamedTextColor.RED);
            playNear(c, "moloch:furnace", 1.5f, 1f);
        }

        // drums, quicker each phase
        if (half % 29 == 1) playNear(c, "moloch:drums", 1f, phase == 1 ? 1f : phase == 2 ? 1.12f : 1.25f);

        // the fire ring: a roar, then a blast around his feet
        int every = (int) Math.round(ringEvery * 2 * (phase == 3 ? 0.7 : 1.0));
        if (ringWindup < 0 && half % every == 0) {
            ringWindup = 4;
            boss.getWorld().playSound(boss.getLocation(), "minecraft:entity.warden.roar", 2f, 0.8f);
            ring(boss.getLocation(), Particle.SMOKE);
        } else if (ringWindup > 0 && --ringWindup == 0) {
            ringWindup = -1;
            Location at = boss.getLocation();
            ring(at, Particle.FLAME);
            at.getWorld().playSound(at, "minecraft:entity.generic.explode", 2f, 0.6f);
            for (Player p : fighters) {
                Vector d = p.getLocation().toVector().subtract(at.toVector()); d.setY(0);
                if (d.length() > ringRadius) continue;
                p.damage(ringDamage, boss);
                p.setFireTicks(60);
                if (d.lengthSquared() > 1e-4) p.setVelocity(d.normalize().multiply(1.2).setY(0.5));
            }
        }

        // cultists from the doorways, from the second phase on
        if (phase >= 2 && half % (cultistEvery * 2) == 0) {
            minions.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid(); });
            for (int i = 0; i < cultistCount && minions.size() < maxMinions; i++) spawnCultist(c);
        }

        // fire rain in the last phase
        if (phase == 3 && half % 6 == 0) for (Player p : fighters) {
            Location from = p.getLocation().add(rnd.nextDouble() * 6 - 3, 14, rnd.nextDouble() * 6 - 3);
            p.getWorld().spawn(from, SmallFireball.class, f -> {
                f.setDirection(new Vector(0, -1, 0));
                f.setIsIncendiary(false);
                f.setShooter(boss);
            });
        }

        if (System.currentTimeMillis() >= endsAt) lose();
    }

    private void spawnCultist(Location c) {
        double[][] doors = {{30, 12}, {30, -12}, {-30, 12}, {-30, -12}, {12, 30}, {-12, 30}, {12, -30}, {-12, -30}};
        double[] d = doors[rnd.nextInt(doors.length)];
        Location at = new Location(c.getWorld(), c.getX() + d[0], c.getY() - 1, c.getZ() + d[1]);
        Vindicator v = c.getWorld().spawn(at, Vindicator.class, m -> {
            m.customName(Component.text("Cultist of Moloch", NamedTextColor.DARK_RED));
            m.setPersistent(false);
            m.addScoreboardTag(MINION);
        });
        minions.add(v.getUniqueId());
    }

    private void ring(Location at, Particle p) {
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            at.getWorld().spawnParticle(p, at.clone().add(Math.cos(a) * ringRadius, 0.3, Math.sin(a) * ringRadius), 2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    private List<Player> fighters() {
        List<Player> out = new ArrayList<>();
        World w = zones.world();
        if (w == null) return out;
        for (Player p : w.getPlayers())
            if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead() && zones.inArena(p.getLocation())) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- endings

    private void win() {
        Location c = zones.arenaCentre();
        cleanup();
        plugin.hunger().reset();
        Bukkit.broadcast(Component.text("MOLOCH IS BANISHED.", NamedTextColor.GOLD, TextDecoration.BOLD));
        UUID top = damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (Map.Entry<UUID, Double> e : damage.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || e.getValue() < minDamage) continue;
            for (String cmd : participantCommands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            p.sendMessage(Component.text("You dealt " + Math.round(e.getValue()) + " damage to Moloch. Take your reward.", NamedTextColor.GOLD));
        }
        if (top != null) {
            Player p = Bukkit.getPlayer(top);
            if (p != null) {
                for (String cmd : topCommands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
                Bukkit.broadcast(Component.text(p.getName() + " struck the hardest blows against Moloch.", NamedTextColor.YELLOW));
            }
        }
        if (c != null) for (Player p : c.getWorld().getPlayers()) p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1f);
    }

    private void lose() {
        Location c = zones.arenaCentre();
        if (boss != null && boss.isValid()) boss.getWorld().spawnParticle(Particle.LAVA, boss.getLocation(), 60, 2, 2, 2, 0);
        cleanup();
        plugin.hunger().reset();
        bloodMoonUntil = System.currentTimeMillis() + bloodMoonHours * 3600_000L;
        store.set("blood-moon-until", bloodMoonUntil);
        store.save();
        Bukkit.broadcast(Component.text("Moloch has fed, and sinks back into the dark.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("A blood moon hangs over his realm for " + bloodMoonHours + " hours. Its creatures are stronger.", NamedTextColor.RED));
        if (c != null) playNear(c, "moloch:awaken", 1f, 0.8f);
    }

    /** Admin stop: remove him with no rewards and no blood moon. */
    void abort() {
        if (!active) return;
        cleanup();
        Bukkit.broadcast(Component.text("Moloch's awakening was called off.", NamedTextColor.GRAY));
    }

    private void cleanup() {
        active = false;
        if (boss != null && boss.isValid()) boss.remove();
        boss = null;
        for (UUID id : minions) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        minions.clear();
        hideAll(bar, barViewers);
        Location c = zones.arenaCentre();
        if (c != null) c.getWorld().getChunkAt(c).removePluginChunkTicket(plugin);
    }

    // ---------------------------------------------------------------- the blood moon

    boolean bloodMoon() { return bloodMoonUntil > System.currentTimeMillis(); }

    private void tickBloodMoon() {
        if (!bloodMoon()) {
            if (moonBar != null) { hideAll(moonBar, moonViewers); moonBar = null; }
            return;
        }
        if (moonBar == null) moonBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        int left = (int) ((bloodMoonUntil - System.currentTimeMillis()) / 1000);
        moonBar.name(Component.text("Blood Moon  ·  " + CurseManager.clock(left), NamedTextColor.RED));
        moonBar.progress(Math.max(0f, Math.min(1f, left / (bloodMoonHours * 3600f))));
        World w = zones.world();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean in = w != null && p.getWorld().equals(w);
            if (in && moonViewers.add(p.getUniqueId())) p.showBossBar(moonBar);
            if (!in && moonViewers.remove(p.getUniqueId())) p.hideBossBar(moonBar);
        }
    }

    // ---------------------------------------------------------------- events

    /** Only players can hurt him: no lava, void, suffocation or command cheese. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBossHurt(EntityDamageEvent e) {
        if (!isBoss(e.getEntity())) return;
        if (attacker(e) == null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossHurtRecord(EntityDamageEvent e) {
        if (!isBoss(e.getEntity())) return;
        Player p = attacker(e);
        if (p != null) damage.merge(p.getUniqueId(), e.getFinalDamage(), Double::sum);
    }

    /** His sonic boom is his fiery breath: it burns, but it's capped so it can't one-shot people. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHits(EntityDamageByEntityEvent e) {
        if (!isBoss(e.getDamager()) || e.getCause() != EntityDamageEvent.DamageCause.SONIC_BOOM) return;
        e.setDamage(Math.min(e.getDamage(), boomCap));
        e.getEntity().setFireTicks(80);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!isBoss(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        if (active) win();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntityType() == EntityType.WARDEN && blockWardens) {
            CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
            if (r != CreatureSpawnEvent.SpawnReason.CUSTOM && r != CreatureSpawnEvent.SpawnReason.COMMAND && r != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
                e.setCancelled(true);   // only Moloch walks in this world
                return;
            }
        }
        if (bloodMoon() && e.getEntity() instanceof Monster m && zones.inRealm(m.getLocation())) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, true, false));
            m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, true, false));
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean isBoss(Entity e) { return e != null && e.getScoreboardTags().contains(TAG); }

    private static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent ed)) return null;
        Entity src = ed.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Player shooter) return shooter;
        return src instanceof Player p ? p : null;
    }

    private void showNear(BossBar b, Set<UUID> viewers, Location c, double r) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean near = p.getWorld().equals(c.getWorld()) && p.getLocation().distanceSquared(c) < r * r;
            if (near && viewers.add(p.getUniqueId())) p.showBossBar(b);
            if (!near && viewers.remove(p.getUniqueId())) p.hideBossBar(b);
        }
    }

    private void hideAll(BossBar b, Set<UUID> viewers) {
        if (b == null) return;
        for (UUID id : viewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(b); }
        viewers.clear();
    }

    private void playNear(Location c, String sound, float vol, float pitch) {
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) < 200 * 200) p.playSound(c, sound, vol, pitch);
    }

    private void sayNear(Location c, String msg, NamedTextColor col) {
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) < 200 * 200) p.sendMessage(Component.text(msg, col));
    }

    String status() {
        if (!active) return bloodMoon() ? "asleep (blood moon for " + CurseManager.clock((int) ((bloodMoonUntil - System.currentTimeMillis()) / 1000)) + ")" : "asleep";
        return "AWAKE, " + (boss != null ? Math.round(boss.getHealth()) : 0) + "/" + Math.round(maxHp) + " health";
    }
}
