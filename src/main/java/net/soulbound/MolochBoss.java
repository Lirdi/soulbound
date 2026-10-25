package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Moloch's awakening, fought in the arena under the pyramid by everyone together:
 * first waves of his servants pour in through the doorways, then Moloch himself rises on the dais.
 * Kill him in time and everyone who hurt him is rewarded; fail and he goes back to sleep fed,
 * and a blood moon hangs over his realm. He only ever fights players, and never blinds them.
 */
final class MolochBoss implements Listener {
    static final String TAG = "moloch_boss", MINION = "moloch_minion";

    private enum Stage { WAVE, BREAK, BOSS }

    private static final List<String> DEFAULT_WAVES = List.of("vindicator:4", "blaze:3,magma_cube:2", "wither_skeleton:3,piglin_brute:1");
    private static final List<String> DEFAULT_WAVE_NAMES = List.of("The cult rises", "Fire from below", "His honour guard");
    private static final double[][] DOORS = {{30, 12}, {30, -12}, {-30, 12}, {-30, -12}, {12, 30}, {-12, 30}, {12, -30}, {-12, -30}};

    private final Soulbound plugin;
    private final Store store;
    private final Zones zones;
    private final Random rnd = new Random();
    private BukkitTask task;

    private boolean active;
    private Stage stage;
    private int wave, waveTotal, breakHalves;
    private Warden boss;
    private BossBar bar, moonBar;
    private final Set<UUID> barViewers = new HashSet<>(), moonViewers = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<UUID> minions = new HashSet<>();
    private double maxHp, savedHp;
    private long endsAt, bloodMoonUntil;
    private int phase, half, ringWindup = -1, respawns;
    // his death: ten seconds of rising, spinning and breaking into light, like the ender dragon's
    private static final int DEATH_TICKS = 200;
    private boolean dying;
    private int deathTicks;
    private BukkitTask deathTask;
    private final List<Vector> rays = new ArrayList<>();
    private final List<Double> rayLengths = new ArrayList<>();
    private static final Particle.DustOptions RAY_WHITE = new Particle.DustOptions(Color.fromRGB(255, 255, 255), 2.2f);
    private static final Particle.DustOptions RAY_PINK = new Particle.DustOptions(Color.fromRGB(255, 120, 255), 2.0f);
    private static final Particle.DustOptions RAY_PURPLE = new Particle.DustOptions(Color.fromRGB(190, 40, 255), 1.7f);
    // his collapse: once per fight, low on health, he crashes to the floor and feeds on everyone's strength
    private boolean collapsed, collapsing;
    private int collapseTicks;
    private double healPerTick;
    private BukkitTask collapseTask;
    private Warden collapseBoss;

    // settings
    private double scale, baseHealth, perPlayer, boomCap, ringDamage, ringRadius, minDamage, leashRadius, extraPerPlayer;
    private int fightMinutes, ringEvery, breakSeconds, bloodMoonHours, deathXp;
    private boolean blockWardens, noDarkness, wavesOn;
    private double collapseAt, collapseHeal;
    private int collapseSeconds;
    private List<String> waves = new ArrayList<>(), waveNames = new ArrayList<>(), participantCommands = new ArrayList<>(), topCommands = new ArrayList<>();
    private String phase2Wave, phase3Wave;

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
        leashRadius = c.getDouble("boss.leash-radius", 26);
        minDamage = c.getDouble("boss.reward-min-damage", 10);
        participantCommands = c.getStringList("boss.reward-commands");
        topCommands = c.getStringList("boss.top-damage-commands");
        bloodMoonHours = c.getInt("boss.blood-moon-hours", 24);
        blockWardens = c.getBoolean("boss.block-other-wardens", true);
        noDarkness = c.getBoolean("boss.no-darkness", true);
        deathXp = Math.max(0, c.getInt("boss.death-xp", 1500));
        wavesOn = c.getBoolean("boss.waves-enabled", false);
        collapseAt = c.getDouble("boss.collapse-at", 0.25);
        collapseSeconds = Math.max(2, c.getInt("boss.collapse-seconds", 8));
        collapseHeal = c.getDouble("boss.collapse-heal", 0.15);
        waves = c.isList("boss.waves") ? c.getStringList("boss.waves") : DEFAULT_WAVES;
        waveNames = c.isList("boss.wave-names") ? c.getStringList("boss.wave-names") : DEFAULT_WAVE_NAMES;
        breakSeconds = Math.max(2, c.getInt("boss.wave-break-seconds", 6));
        extraPerPlayer = c.getDouble("boss.wave-extra-per-player", 0.5);
        phase2Wave = c.getString("boss.phase-2-wave", "vindicator:3");
        phase3Wave = c.getString("boss.phase-3-wave", "wither_skeleton:2,blaze:2");
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHalf, 10L, 10L); }

    void stop() {
        if (task != null) task.cancel();
        if (active && dying) win();   // he was already dying: the players won
        else if (active) {   // a restart mid-fight: tidy up, and wake him again as soon as the server's back
            cleanup();
            plugin.hunger().awakenNow();
        }
        hideAll(moonBar, moonViewers);
    }

    boolean active() { return active; }
    Warden boss() { return boss; }

    // ---------------------------------------------------------------- the awakening

    /** Why the fight can't start right now, or null if it can. */
    String cantBegin() {
        World w = zones.world();
        if (w == null || zones.arenaCentre() == null) return "Moloch's realm (" + zones.worldKey + ") isn't loaded.";
        if (w.getDifficulty() == Difficulty.PEACEFUL) return "The difficulty is Peaceful, and Peaceful deletes hostile mobs, Moloch too. Run /difficulty normal first.";
        return null;
    }

    /** Start the fight. False if it can't start (see cantBegin). */
    boolean begin() {
        if (active) return true;
        String no = cantBegin();
        if (no != null) { plugin.getLogger().warning("Moloch can't rise: " + no); return false; }
        World w = zones.world();
        Location c = zones.arenaCentre();
        w.getChunkAt(c).addPluginChunkTicket(plugin);
        damage.clear();
        minions.clear();
        half = 0; respawns = 0; ringWindup = -1; phase = 1;
        endsAt = System.currentTimeMillis() + fightMinutes * 60_000L;
        active = true;
        bar = BossBar.bossBar(Component.text("Moloch", NamedTextColor.DARK_RED, TextDecoration.BOLD), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        Bukkit.broadcast(Component.text("MOLOCH STIRS.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("Everyone to the arena under the pyramid. " + (wavesOn ? "Fight through his servants, then bring" : "Bring") + " him down within " + fightMinutes + " minutes.", NamedTextColor.RED));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), "moloch:awaken", 1f, 1f);
        wave = -1;
        collapsed = false; collapsing = false;
        if (!wavesOn || waves.isEmpty()) raiseMoloch();
        else { stage = Stage.BREAK; breakHalves = 6; }
        return true;
    }

    // ---------------------------------------------------------------- waves

    private void startWave(int i) {
        wave = i;
        stage = Stage.WAVE;
        Location c = zones.arenaCentre();
        int spawned = spawnGroup(waves.get(i), c);
        waveTotal = Math.max(1, spawned);
        String name = i < waveNames.size() ? waveNames.get(i) : "Wave " + (i + 1);
        titleNear(c, Component.text("Wave " + (i + 1) + " of " + waves.size(), NamedTextColor.RED, TextDecoration.BOLD), Component.text(name, NamedTextColor.GOLD));
        playNear(c, "moloch:drums", 1f, 1f);
    }

    /** Spawn a group like "blaze:3,magma_cube:2" at the doorways, scaled up for more players. Returns how many. */
    private int spawnGroup(String spec, Location c) {
        int fighters = Math.max(1, fighters().size()), n = 0;
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) continue;
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase(Locale.ROOT)));
            if (type == null || !type.isSpawnable() || !type.isAlive()) { plugin.getLogger().warning("Unknown wave mob '" + kv[0] + "'"); continue; }
            int count;
            try { count = Integer.parseInt(kv[1].trim()); } catch (NumberFormatException ex) { continue; }
            count = (int) Math.round(count * (1 + extraPerPlayer * (fighters - 1)));
            for (int k = 0; k < count; k++) { spawnMinion(type, c); n++; }
        }
        return n;
    }

    private void spawnMinion(EntityType type, Location c) {
        double[] d = DOORS[rnd.nextInt(DOORS.length)];
        Location at = new Location(c.getWorld(), c.getX() + d[0] + rnd.nextDouble() - 0.5, c.getY() - 1, c.getZ() + d[1] + rnd.nextDouble() - 0.5);
        Entity e = c.getWorld().spawnEntity(at, type);
        tagMinion(e);
        String name = switch (type.getKey().getKey()) {
            case "vindicator", "pillager", "evoker" -> "Cultist of Moloch";
            case "blaze" -> "Ember Spirit";
            case "magma_cube" -> "Living Slag";
            case "wither_skeleton", "skeleton" -> "Temple Guard";
            case "piglin_brute" -> "Moloch's Champion";
            default -> null;
        };
        if (name != null) e.customName(Component.text(name, NamedTextColor.DARK_RED));
    }

    private void tagMinion(Entity e) {
        e.addScoreboardTag(MINION);
        e.setPersistent(false);
        if (e instanceof Mob m) m.setRemoveWhenFarAway(false);
        minions.add(e.getUniqueId());
    }

    private int minionsLeft() {
        minions.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
        return minions.size();
    }

    // ---------------------------------------------------------------- Moloch rises

    private void raiseMoloch() {
        stage = Stage.BOSS;
        World w = zones.world();
        Location c = zones.arenaCentre();
        int fighters = Math.max(1, Math.max(fighters().size(), w.getPlayers().size()));
        maxHp = Math.min(1024, baseHealth + perPlayer * fighters);
        savedHp = maxHp;
        phase = 1; ringWindup = -1;
        spawnBoss(savedHp);
        w.spawnParticle(Particle.LAVA, c, 80, 3, 2, 3, 0);
        playNear(c, "moloch:awaken", 1.5f, 1f);
        titleNear(c, Component.text("MOLOCH", NamedTextColor.DARK_RED, TextDecoration.BOLD), Component.text("has risen", NamedTextColor.RED));
        Bukkit.broadcast(Component.text("MOLOCH HAS RISEN.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
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

    private static void set(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    // ---------------------------------------------------------------- every half second

    private void tickHalf() {
        tickBloodMoon();
        if (!active) return;
        half++;
        Location c = zones.arenaCentre();
        if (c == null || dying) return;
        if (System.currentTimeMillis() >= endsAt) { lose(); return; }
        long left = Math.max(0, endsAt - System.currentTimeMillis()) / 1000;
        keepMinionsIn(c);
        showNear(bar, barViewers, c, 160);

        switch (stage) {
            case BREAK -> {
                bar.name(Component.text("Moloch stirs  ·  " + CurseManager.clock((int) left), NamedTextColor.DARK_RED, TextDecoration.BOLD));
                bar.progress(1f);
                if (--breakHalves <= 0) {
                    if (wave + 1 < waves.size()) startWave(wave + 1);
                    else raiseMoloch();
                }
            }
            case WAVE -> {
                int remaining = minionsLeft();
                bar.name(Component.text("Wave " + (wave + 1) + "/" + waves.size() + "  ·  " + remaining + " left  ·  " + CurseManager.clock((int) left), NamedTextColor.RED, TextDecoration.BOLD));
                bar.progress(Math.max(0f, Math.min(1f, (float) remaining / waveTotal)));
                if (remaining == 0) {
                    stage = Stage.BREAK;
                    breakHalves = breakSeconds * 2;
                    sayNear(c, wave + 1 < waves.size() ? "Wave cleared. More are coming..." : "The last of his servants falls. The ground shakes...", NamedTextColor.GOLD);
                }
            }
            case BOSS -> tickMoloch(c, left);
        }
    }

    private void tickMoloch(Location c, long left) {
        if (boss == null || !boss.isValid()) {
            if (boss != null && boss.getHealth() <= 0) return;   // killed: the death event finishes it
            if (c.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
                Bukkit.broadcast(Component.text("Moloch can't exist on Peaceful difficulty. The awakening is called off; set /difficulty normal and try again.", NamedTextColor.RED));
                abort();
                return;
            }
            if (++respawns > 20) {   // something keeps removing him: stop rather than loop forever
                plugin.getLogger().warning("Moloch keeps vanishing straight after he spawns (another plugin removing mobs, or mob limits?). Stopping the fight.");
                Bukkit.broadcast(Component.text("Moloch couldn't hold his form. The awakening is called off.", NamedTextColor.RED));
                abort();
                return;
            }
            spawnBoss(savedHp);                                  // he dug away or was unloaded: he rises again as he was
            c.getWorld().spawnParticle(Particle.LAVA, c, 40, 2, 1, 2, 0);
        }
        savedHp = boss.getHealth();
        if (half % 40 == 0) respawns = 0;
        if (collapsing) {   // lying on the floor, feeding: the collapse task does the rest
            bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / maxHp)));
            bar.name(Component.text("Moloch feeds  ·  " + CurseManager.clock((int) left), NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
            return;
        }

        // keep him in his hall
        Location b = boss.getLocation();
        if (Math.max(Math.abs(b.getX() - c.getX()), Math.abs(b.getZ() - c.getZ())) > leashRadius || b.getY() < c.getY() - 4 || b.getY() > c.getY() + 30)
            boss.teleport(c);

        // players only: forget anything else he's angry at, and hunt the nearest player
        for (int i = 0; i < 8; i++) {
            Entity angry = boss.getEntityAngryAt();
            if (angry == null || angry instanceof Player) break;
            boss.clearAnger(angry);
        }
        if (boss.getTarget() != null && !(boss.getTarget() instanceof Player)) boss.setTarget(null);
        List<Player> fighters = fighters();
        if (!fighters.isEmpty()) {
            Player near = fighters.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).get();
            boss.setAnger(near, 100);   // always hunting, so he never digs back down
        }

        bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / maxHp)));
        bar.name(Component.text("Moloch  ·  " + CurseManager.clock((int) left), NamedTextColor.DARK_RED, TextDecoration.BOLD));

        // phases: reinforcements at two thirds and one third
        double ratio = boss.getHealth() / maxHp;
        int now = ratio > 0.66 ? 1 : ratio > 0.33 ? 2 : 3;
        if (now > phase) {
            phase = now;
            sayNear(c, phase == 2 ? "The furnace in Moloch's belly roars open!" + (wavesOn ? " His cult answers the call." : "") : "MOLOCH HUNGERS. Fire falls from the vault!", NamedTextColor.RED);
            playNear(c, "moloch:furnace", 1.5f, 1f);
            if (wavesOn) spawnGroup(phase == 2 ? phase2Wave : phase3Wave, c);
        }

        // low on health, he collapses (once a fight)
        if (!collapsed && collapseAt > 0 && ratio <= collapseAt) { startCollapse(); return; }

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

        // fire rain in the last phase
        if (phase == 3 && half % 6 == 0) for (Player p : fighters) {
            Location from = p.getLocation().add(rnd.nextDouble() * 6 - 3, 14, rnd.nextDouble() * 6 - 3);
            p.getWorld().spawn(from, SmallFireball.class, f -> {
                f.setDirection(new Vector(0, -1, 0));
                f.setIsIncendiary(false);
                f.setShooter(boss);
            });
        }
    }

    /** Servants that wander out of the hall are sent back to a doorway. */
    private void keepMinionsIn(Location c) {
        if (half % 4 != 0) return;
        for (UUID id : new ArrayList<>(minions)) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) continue;
            Location l = e.getLocation();
            if (Math.max(Math.abs(l.getX() - c.getX()), Math.abs(l.getZ() - c.getZ())) > 33 || l.getY() < c.getY() - 6) {
                double[] d = DOORS[rnd.nextInt(DOORS.length)];
                e.teleport(new Location(c.getWorld(), c.getX() + d[0], c.getY() - 1, c.getZ() + d[1]));
            }
        }
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

    // ---------------------------------------------------------------- his collapse

    /** He freezes and crashes to the floor, then lies there feeding: healing, and draining everyone's hunger to nothing. */
    private void startCollapse() {
        collapsed = true;
        collapsing = true;
        collapseTicks = 0;
        ringWindup = -1;
        collapseBoss = boss;
        healPerTick = maxHp * collapseHeal / (collapseSeconds * 20.0);
        boss.setAI(false);
        boss.setInvulnerable(true);
        boss.setPose(Pose.SLEEPING, true);   // renders him lying flat on his side
        Location at = boss.getLocation();
        World w = at.getWorld();
        w.playSound(at, "minecraft:entity.generic.explode", 3f, 0.5f);
        w.playSound(at, "minecraft:entity.warden.death", 3f, 0.6f);
        w.playSound(at, "minecraft:block.anvil.land", 2f, 0.5f);
        fx(Particle.BLOCK, at.clone().add(0, 0.3, 0), 250, scale * 1.4, 0.15, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
        fx(Particle.CLOUD, at.clone().add(0, 0.5, 0), 80, scale * 1.2, 0.05, null);
        for (Player p : fighters()) {   // the floor jolts under everyone's feet
            if (p.getLocation().distanceSquared(at) < 16 * 16) p.setVelocity(p.getVelocity().setY(0.45));
        }
        titleNear(at, Component.text("MOLOCH COLLAPSES", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD),
                Component.text("...and feeds on your strength", NamedTextColor.GRAY));
        sayNear(at, "Moloch crashes to the floor and drinks the strength out of everyone in the hall!", NamedTextColor.DARK_PURPLE);
        collapseTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCollapse, 1L, 1L);
    }

    private void tickCollapse() {
        if (!active || dying || boss == null || boss != collapseBoss || !boss.isValid()) { endCollapse(); return; }
        collapseTicks++;
        int total = collapseSeconds * 20;
        double t = collapseTicks / (double) total;
        boss.setHealth(Math.min(maxHp, boss.getHealth() + healPerTick));
        savedHp = boss.getHealth();
        Location core = boss.getLocation().add(0, 1.2, 0);

        // everyone's hunger bar drains to nothing by the time he stands, and their souls stream into him
        int food = (int) Math.floor(20 * (1 - t));
        for (Player p : fighters()) {
            if (p.getFoodLevel() > food) p.setFoodLevel(food);
            p.setSaturation(0f);
            if (collapseTicks % 3 == 0) {
                Location eye = p.getEyeLocation();
                Vector to = core.toVector().subtract(eye.toVector());
                for (int i = 0; i < 3; i++)
                    fx(Particle.SOUL, eye.clone().add(to.clone().multiply(rnd.nextDouble())), 1, 0.1, 0.01, null);
            }
        }
        if (collapseTicks % 4 == 0) fx(Particle.SCULK_SOUL, core, 4, scale * 0.8, 0.02, null);
        if (collapseTicks % 16 == 0) core.getWorld().playSound(core, "minecraft:entity.warden.heartbeat", 3f, 0.6f);
        if (collapseTicks >= total) endCollapse();
    }

    /** He gets back up, healed, and the fight goes on. */
    private void endCollapse() {
        if (collapseTask != null) { collapseTask.cancel(); collapseTask = null; }
        if (!collapsing) return;
        collapsing = false;
        Warden w = collapseBoss;
        collapseBoss = null;
        if (w == null || !w.isValid() || dying) return;
        w.setPose(Pose.STANDING, false);
        w.setAI(true);
        w.setInvulnerable(false);
        for (Player p : fighters()) { p.setFoodLevel(0); p.setSaturation(0f); }
        w.getWorld().playSound(w.getLocation(), "minecraft:entity.warden.roar", 3f, 0.7f);
        fx(Particle.SCULK_SOUL, w.getLocation().add(0, 3, 0), 40, scale, 0.05, null);
        sayNear(w.getLocation(), "Moloch rises again, gorged on your strength!", NamedTextColor.DARK_RED);
    }

    // ---------------------------------------------------------------- his death

    private void startDeath(Player killer) {
        dying = true;
        deathTicks = 0;
        if (collapsing) endCollapse();
        Location c = zones.arenaCentre();
        boss.setAI(false);
        boss.setGravity(false);
        boss.setInvulnerable(true);
        boss.setSilent(true);
        boss.setHealth(1);
        for (UUID id : minions) {   // any servants left vanish with him
            Entity m = Bukkit.getEntity(id);
            if (m == null) continue;
            fx(Particle.POOF, m.getLocation().add(0, 1, 0), 12, 0.4, 0.02, null);
            m.remove();
        }
        minions.clear();
        // the ender dragon's rays: fixed random directions that turn with him, appearing faster and faster
        rays.clear();
        rayLengths.clear();
        Random r = new Random(432L);
        for (int i = 0; i < 32; i++) {
            Vector d = new Vector(r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1);
            if (d.lengthSquared() < 1e-3) d = new Vector(0, 1, 0);
            rays.add(d.normalize());
            rayLengths.add(0.4 + r.nextDouble() * 0.6);
        }
        bar.name(Component.text("Moloch is falling", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        bar.progress(0f);
        for (Player p : c.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(c) < 200 * 200) p.playSound(c, "moloch:death", 6f, 1f);
            else p.playSound(p.getLocation(), "moloch:death", 0.5f, 1f);   // the whole realm hears him fall
        }
        titleNear(c, Component.text("MOLOCH FALLS", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(killer != null ? "struck down by " + killer.getName() : "", NamedTextColor.YELLOW));
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDeath, 1L, 1L);
    }

    /** Every tick of his death, as the ender dragon dies: he rises and spins while rays of light break out of him. */
    private void tickDeath() {
        if (boss == null || !boss.isValid()) { finishDeath(); return; }
        deathTicks++;
        double t = deathTicks / (double) DEATH_TICKS;
        float spin = (float) (6 + 10 * t);
        Location at = boss.getLocation().add(0, 0.1, 0);
        at.setYaw(at.getYaw() + spin);
        at.setPitch(0);
        boss.teleport(at);
        Location core = at.clone().add(0, 2.9 * scale * 0.5, 0);

        // rays of white light fading to purple, more of them and longer as he goes
        double turn = Math.toRadians(spin);
        for (Vector d : rays) d.rotateAroundY(turn);
        if (deathTicks % 2 == 0) {
            int shown = Math.min(rays.size(), (int) ((t + t * t) / 2 * 60));
            double reach = 4 + 14 * t;
            for (int i = 0; i < shown; i++) {
                Vector d = rays.get(i);
                double len = reach * rayLengths.get(i);
                for (double r = 0.8; r < len; r += 0.75) {
                    double f = r / len;
                    fx(Particle.DUST, core.clone().add(d.clone().multiply(r)), 1, 0.02, 0, f < 0.35 ? RAY_WHITE : f < 0.7 ? RAY_PINK : RAY_PURPLE);
                }
            }
        }

        // the last second: he bursts, explosion after explosion
        if (deathTicks >= DEATH_TICKS - 20) {
            double s = 4 + scale;
            fx(Particle.EXPLOSION_EMITTER, core.clone().add((rnd.nextDouble() - 0.5) * s, (rnd.nextDouble() - 0.5) * s, (rnd.nextDouble() - 0.5) * s), 1, 0, 0, null);
        }

        // experience pours out of him, as it does from the dragon
        if (deathXp > 0 && deathTicks > 150 && deathTicks % 5 == 0) dropXp(core, (int) (deathXp * 0.08));
        if (deathTicks >= DEATH_TICKS) {
            if (deathXp > 0) dropXp(core, (int) (deathXp * 0.2));
            finishDeath();
        }
    }

    private void dropXp(Location at, int amount) {
        while (amount > 0) {
            int v = Math.min(amount, 50);
            amount -= v;
            at.getWorld().spawn(at, ExperienceOrb.class, o -> {
                o.setExperience(v);
                o.setVelocity(new Vector(rnd.nextGaussian() * 0.15, 0.2 + rnd.nextDouble() * 0.2, rnd.nextGaussian() * 0.15));
            });
        }
    }

    private void finishDeath() {
        if (deathTask != null) { deathTask.cancel(); deathTask = null; }
        if (boss != null && boss.isValid()) boss.remove();
        win();
    }

    /** Particles everyone in the hall can see, however far back they stand. */
    private void fx(Particle p, Location at, int count, double spread, double speed, Object data) {
        at.getWorld().spawnParticle(p, at, count, spread, spread, spread, speed, data, true);
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
        dying = false;
        if (deathTask != null) { deathTask.cancel(); deathTask = null; }
        if (collapseTask != null) { collapseTask.cancel(); collapseTask = null; }
        collapsing = false;
        collapseBoss = null;
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

    /** Only players can hurt him: no lava, void, suffocation, minions or command cheese. */
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

    /** The killing blow doesn't kill him outright: it starts his death. Nothing hurts him while he dies. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossLethal(EntityDamageEvent e) {
        if (!active || !isBoss(e.getEntity()) || !(e.getEntity() instanceof Warden w)) return;
        if (dying || collapsing) { e.setCancelled(true); return; }
        if (w.getHealth() - e.getFinalDamage() > 0) return;
        Player p = attacker(e);
        if (p != null) damage.merge(p.getUniqueId(), w.getHealth(), Double::sum);
        e.setCancelled(true);
        startDeath(p);
    }

    /** He only hurts players. His sonic boom is his fiery breath: it burns, but it's capped so it can't one-shot people. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHits(EntityDamageByEntityEvent e) {
        if (!isBoss(e.getDamager())) return;
        if (!(e.getEntity() instanceof Player)) { e.setCancelled(true); return; }
        if (e.getCause() == EntityDamageEvent.DamageCause.SONIC_BOOM) {
            e.setDamage(Math.min(e.getDamage(), boomCap));
            e.getEntity().setFireTicks(80);
        }
    }

    /** He never targets mobs, and his servants never turn on him or each other. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Entity t = e.getTarget();
        if (t == null) return;
        if (isBoss(e.getEntity()) && !(t instanceof Player)) { e.setCancelled(true); return; }
        if (e.getEntity().getScoreboardTags().contains(MINION) && !(t instanceof Player)) e.setCancelled(true);
    }

    /** His servants don't hurt each other either (stray blaze fire, mostly). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinionFriendlyFire(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity sh) src = sh;
        if (src.getScoreboardTags().contains(MINION) && e.getEntity().getScoreboardTags().contains(MINION)) e.setCancelled(true);
    }

    /** No Darkness from Moloch: players need to see the fight. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDarkness(EntityPotionEffectEvent e) {
        if (!noDarkness || e.getCause() != EntityPotionEffectEvent.Cause.WARDEN) return;
        if (e.getModifiedType().equals(PotionEffectType.DARKNESS)) e.setCancelled(true);
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
        // magma cubes that split during a wave are still his servants
        if (active && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SLIME_SPLIT && zones.inArena(e.getLocation())) tagMinion(e.getEntity());
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

    private void titleNear(Location c, Component title, Component sub) {
        Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700)));
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) < 200 * 200) p.showTitle(t);
    }

    String status() {
        if (!active) return bloodMoon() ? "asleep (blood moon for " + CurseManager.clock((int) ((bloodMoonUntil - System.currentTimeMillis()) / 1000)) + ")" : "asleep";
        if (stage != Stage.BOSS) return "stirring: wave " + Math.max(1, wave + 1) + " of " + waves.size();
        return "AWAKE, " + (boss != null ? Math.round(boss.getHealth()) : 0) + "/" + Math.round(maxHp) + " health";
    }
}
