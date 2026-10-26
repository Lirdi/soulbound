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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PiglinBrute;
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
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
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
 * Moloch's awakening, fought in the arena under the pyramid by everyone together.
 *
 * He scales live with the number of fighters in the hall. Between his normal warden attacks he picks moves:
 * the bull charge (dodge it into a pillar and he topples over, stunned and open), the leap slam, furnace breath,
 * the fire ring and the chain pull (for anyone hanging back). Arrows only ever do half damage, and his Soulfire Ward
 * burns them and throws fire back, more so when people lean on bows. At 75% his golden champions shield him,
 * at 50% he screams, at 25% he collapses and feeds, and below that he's in a frenzy. When he dies he cracks, crashes to the floor and bursts apart.
 */
final class MolochBoss implements Listener {
    static final String TAG = "moloch_boss", MINION = "moloch_minion";
    /** Ways mobs appear by themselves (as opposed to plugins, commands, spawn eggs or players building golems). */
    private static final Set<String> NATURAL_REASONS = Set.of("NATURAL", "CHUNK_GEN", "REINFORCEMENTS", "PATROL", "JOCKEY", "MOUNT", "TRAP",
            "NETHER_PORTAL", "VILLAGE_DEFENSE", "VILLAGE_INVASION", "SILVERFISH_BLOCK", "RAID", "LIGHTNING", "ENDER_PEARL", "SPAWNER", "TRIAL_SPAWNER");

    private enum Stage { WAVE, BREAK, BOSS }
    private enum Move { NONE, RING, CHARGE, LEAP, BREATH, CHAIN, STUN, GRAB }

    private static final List<String> DEFAULT_WAVES = List.of("vindicator:4", "blaze:3,magma_cube:2", "wither_skeleton:3,piglin_brute:1");
    private static final List<String> DEFAULT_WAVE_NAMES = List.of("The cult rises", "Fire from below", "His honour guard");
    private static final double[][] DOORS = {{30, 12}, {30, -12}, {-30, 12}, {-30, -12}, {12, 30}, {-12, 30}, {12, -30}, {-12, -30}};
    private static final double REAL_HP = 1024;   // the game's cap: his real health; crowd scaling works through damage instead

    private static final Particle.DustOptions WARN = new Particle.DustOptions(Color.fromRGB(200, 20, 10), 1.6f);
    private static final Particle.DustOptions CHAIN = new Particle.DustOptions(Color.fromRGB(95, 95, 105), 1.4f);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 196, 40), 1.4f);

    private final Soulbound plugin;
    private final Store store;
    private final Zones zones;
    private final Random rnd = new Random();
    private BukkitTask task, fastTask;

    // the fight
    private boolean active;
    private Stage stage;
    private int wave, waveTotal, breakHalves;
    private Warden boss;
    private BossBar bar, moonBar;
    private final Set<UUID> barViewers = new HashSet<>(), moonViewers = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<UUID> minions = new HashSet<>(), champions = new HashSet<>();
    private double savedHp, hpScale = 1, baseSpeed = 0.3;
    private long endsAt, bloodMoonUntil;
    private int phase, half, respawns, crowd = 1;
    private boolean championsCalled, shieldUp, screamed, collapsed, collapsing, frenzy, emerging;
    private static final int EMERGE_TICKS = 134;

    // his moves
    private Move move = Move.NONE, lastMove = Move.NONE;
    private int moveTick, nextMoveIn = 100;
    private Vector moveDir;
    private Location moveTarget, lastPos;
    private Vector leapV;
    // the grab: who he's holding, how hard the others have hit him since, and whether we're letting go on purpose
    private double grabSqueeze, grabThrowDamage, grabBreak, grabDamage;
    private int grabSeconds;
    private boolean releasing;
    private int edgeHalves, stuckHalves, escapeCooldown;
    private Location lastSpot;
    private Player victim;
    private final Set<UUID> moveHit = new HashSet<>();
    private final Map<UUID, Integer> farTicks = new HashMap<>(), lastSwing = new HashMap<>();
    private boolean synthetic;
    private int fastTicks;

    // the Soulfire Ward and how much of the damage is coming from bows
    private int wardLeft, wardCooldown, wardNext;
    private double projDmg, allDmg;

    // his collapse and death
    private int collapseTicks, deathTicks;
    private double healPerTick;
    private BukkitTask collapseTask, deathTask;
    private Warden collapseBoss;
    private boolean dying;
    private static final int DEATH_TICKS = 200;

    // settings
    private double scale, hpBase, hpPerFighter, boomCap, meleeDamage, ringDamage, ringRadius, minDamage, leashRadius, extraPerPlayer;
    private double chargeDamage, slamDamage, slamRadius, breathDamage, breathRange, chainRange, stunMultiplier, projectileMultiplier;
    private double championShield, champBase, champPerFighter, champHealth, frenzySpeed, screamAt, collapseAt, collapseHeal, moveGap;
    private int fightMinutes, breakSeconds, bloodMoonHours, deathXp, stunSeconds, wardSeconds, wardEvery, champMax;
    private int screamNausea, screamWeakness, collapseSeconds;
    private boolean blockWardens, noDarkness, wavesOn, templeNoSpawns;
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
        hpBase = c.getDouble("boss.effective-health-base", 800);
        hpPerFighter = c.getDouble("boss.effective-health-per-fighter", 700);
        fightMinutes = c.getInt("boss.fight-minutes", 30);
        meleeDamage = c.getDouble("boss.melee-damage", 14);
        boomCap = c.getDouble("boss.sonic-boom-max-damage", 8);
        projectileMultiplier = c.getDouble("boss.projectile-damage", 0.5);
        moveGap = Math.max(2, c.getDouble("boss.seconds-between-moves", 7));
        ringDamage = c.getDouble("boss.fire-ring-damage", 6);
        ringRadius = c.getDouble("boss.fire-ring-radius", 8);
        chargeDamage = c.getDouble("boss.charge-damage", 12);
        grabSqueeze = c.getDouble("boss.grab-squeeze-damage", 1.5);
        grabThrowDamage = c.getDouble("boss.grab-throw-damage", 2);
        grabBreak = c.getDouble("boss.grab-break-damage-per-fighter", 30);
        grabSeconds = Math.max(1, c.getInt("boss.grab-hold-seconds", 3));
        stunSeconds = Math.max(1, c.getInt("boss.stun-seconds", 5));
        stunMultiplier = c.getDouble("boss.stun-damage-multiplier", 2.0);
        slamDamage = c.getDouble("boss.slam-damage", 12);
        slamRadius = c.getDouble("boss.slam-radius", 6);
        breathDamage = c.getDouble("boss.breath-damage", 3);
        breathRange = c.getDouble("boss.breath-range", 14);
        chainRange = c.getDouble("boss.chain-range", 14);
        wardSeconds = Math.max(2, c.getInt("boss.ward-seconds", 8));
        wardEvery = Math.max(10, c.getInt("boss.ward-every-seconds", 35));
        championShield = c.getDouble("boss.champion-shield", 0.4);
        champBase = c.getDouble("boss.champions", 2);
        champPerFighter = c.getDouble("boss.champions-per-extra-fighter", 0.5);
        champMax = c.getInt("boss.champions-max", 8);
        champHealth = c.getDouble("boss.champion-health", 60);
        frenzySpeed = c.getDouble("boss.frenzy-speed", 1.3);
        leashRadius = c.getDouble("boss.emergency-return-distance", 70);
        minDamage = c.getDouble("boss.reward-min-damage", 10);
        participantCommands = c.getStringList("boss.reward-commands");
        topCommands = c.getStringList("boss.top-damage-commands");
        bloodMoonHours = c.getInt("boss.blood-moon-hours", 24);
        blockWardens = c.getBoolean("boss.block-other-wardens", true);
        templeNoSpawns = c.getBoolean("moloch.temple-no-natural-spawns", true);
        noDarkness = c.getBoolean("boss.no-darkness", true);
        deathXp = Math.max(0, c.getInt("boss.death-xp", 1500));
        screamAt = c.getDouble("boss.scream-at", 0.5);
        screamNausea = Math.max(1, c.getInt("boss.scream-nausea-seconds", 6));
        screamWeakness = Math.max(0, c.getInt("boss.scream-weakness-seconds", 6));
        collapseAt = c.getDouble("boss.collapse-at", 0.25);
        collapseSeconds = Math.max(2, c.getInt("boss.collapse-seconds", 8));
        collapseHeal = c.getDouble("boss.collapse-heal", 0.15);
        wavesOn = c.getBoolean("boss.waves-enabled", false);
        waves = c.isList("boss.waves") ? c.getStringList("boss.waves") : DEFAULT_WAVES;
        waveNames = c.isList("boss.wave-names") ? c.getStringList("boss.wave-names") : DEFAULT_WAVE_NAMES;
        breakSeconds = Math.max(2, c.getInt("boss.wave-break-seconds", 6));
        extraPerPlayer = c.getDouble("boss.wave-extra-per-player", 0.5);
        phase2Wave = c.getString("boss.phase-2-wave", "vindicator:3");
        phase3Wave = c.getString("boss.phase-3-wave", "wither_skeleton:2,blaze:2");
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHalf, 10L, 10L);
        fastTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFast, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        if (fastTask != null) fastTask.cancel();
        if (active && dying) win();   // he was already dying: the players won
        else if (active) {            // a restart mid-fight: tidy up, and wake him again as soon as the server's back
            cleanup();
            plugin.hunger().awakenNow();
        }
        hideAll(moonBar, moonViewers);
    }

    boolean active() { return active; }
    Warden boss() { return boss; }

    /** Which part of the battle music fits this moment, or null when there's no fight. */
    String musicState() {
        if (!active) return null;
        if (dying) return "end";
        if (stage != Stage.BOSS || emerging) return "rise";
        if (collapsing) return "collapse";
        if (frenzy) return "frenzy";
        if (screamed) return "scream";
        if (championsCalled) return "champions";
        return "march";
    }
    Location musicCentre() { return zones.arenaCentre(); }

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
        damage.clear(); minions.clear(); champions.clear(); farTicks.clear(); lastSwing.clear();
        half = 0; respawns = 0; phase = 1; crowd = 1;
        championsCalled = shieldUp = screamed = collapsed = collapsing = frenzy = dying = emerging = false;
        move = lastMove = Move.NONE;
        wardLeft = 0; wardCooldown = 0; wardNext = wardEvery * 20; projDmg = allDmg = 0;
        endsAt = System.currentTimeMillis() + fightMinutes * 60_000L;
        active = true;
        bar = BossBar.bossBar(Component.text("Moloch", NamedTextColor.DARK_RED, TextDecoration.BOLD), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        Bukkit.broadcast(Component.text("MOLOCH STIRS.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("Everyone to the arena under the pyramid. " + (wavesOn ? "Fight through his servants, then bring" : "Bring") + " him down within " + fightMinutes + " minutes.", NamedTextColor.RED));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), "moloch:awaken", 1f, 1f);
        wave = -1;
        if (!wavesOn || waves.isEmpty()) raiseMoloch();
        else { stage = Stage.BREAK; breakHalves = 6; }
        return true;
    }

    // ---------------------------------------------------------------- waves (optional, off by default)

    private void startWave(int i) {
        wave = i;
        stage = Stage.WAVE;
        Location c = zones.arenaCentre();
        waveTotal = Math.max(1, spawnGroup(waves.get(i), c));
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
        Entity e = c.getWorld().spawnEntity(doorSpot(c, rnd.nextInt(DOORS.length)), type);
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

    private Location doorSpot(Location c, int i) {
        double[] d = DOORS[i % DOORS.length];
        return new Location(c.getWorld(), c.getX() + d[0] + rnd.nextDouble() - 0.5, c.getY() - 1, c.getZ() + d[1] + rnd.nextDouble() - 0.5);
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

    private int championsLeft() {
        champions.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid() || e.isDead(); });
        return champions.size();
    }

    // ---------------------------------------------------------------- Moloch rises

    private void raiseMoloch() {
        stage = Stage.BOSS;
        Location c = zones.arenaCentre();
        crowd = Math.max(1, fighters().size());
        savedHp = REAL_HP;
        phase = 1;
        nextMoveIn = 100;
        emerging = true;
        spawnBoss(savedHp);
        // he claws his way up out of the floor of the dais (the warden's own emerging animation, about 7 seconds)
        boss.setAI(false);
        boss.setInvulnerable(true);
        Location at = boss.getLocation();
        playNear(c, "moloch:awaken", 1.5f, 1f);
        at.getWorld().playSound(at, "minecraft:entity.warden.emerge", 4f, 0.7f);
        titleNear(c, Component.text("MOLOCH", NamedTextColor.DARK_RED, TextDecoration.BOLD), Component.text("rises from the deep", NamedTextColor.RED));
        Warden rising = boss;
        for (int t = 0; t <= EMERGE_TICKS; t += 10) {
            final int tick = t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!active || boss != rising || !rising.isValid()) return;
                Location l = rising.getLocation();
                fx(Particle.BLOCK, l.clone().add(0, 0.3, 0), 60, scale * 0.9, 0.15, Material.GILDED_BLACKSTONE.createBlockData());
                fx(Particle.LARGE_SMOKE, l.clone().add(0, 0.5, 0), 10, scale * 0.8, 0.03, null);
                if (tick % 40 == 0) {
                    l.getWorld().playSound(l, "minecraft:block.basalt.break", 3f, 0.5f);
                    for (Player p : fighters()) if (p.getLocation().distanceSquared(l) < 18 * 18 && p.isOnGround()) p.setVelocity(p.getVelocity().setY(0.25));
                }
            }, t);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            emerging = false;
            Warden up = boss;   // usually the same Moloch that started rising
            if (!active || up == null || !up.isValid()) return;
            up.setPose(Pose.STANDING, false);
            up.setAI(true);
            up.setInvulnerable(false);
            Location l = up.getLocation();
            l.getWorld().playSound(l, "minecraft:entity.warden.roar", 4f, 0.7f);
            fx(Particle.LAVA, l, 80, 3, 0, null);
            Bukkit.broadcast(Component.text("MOLOCH HAS RISEN.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        }, EMERGE_TICKS);
    }

    private void spawnBoss(double health) {
        Location c = dais();
        boss = c.getWorld().spawn(c, Warden.class, wd -> {
            set(wd, Attribute.SCALE, scale);
            set(wd, Attribute.MAX_HEALTH, REAL_HP);
            set(wd, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            set(wd, Attribute.ATTACK_DAMAGE, meleeDamage);
            wd.customName(Component.text("Moloch", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            wd.setCustomNameVisible(true);
            wd.setPersistent(false);
            wd.setRemoveWhenFarAway(false);
            wd.addScoreboardTag(TAG);
            if (emerging) wd.setPose(Pose.EMERGING, true);
        });
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) { baseSpeed = sp.getBaseValue(); if (frenzy) sp.setBaseValue(baseSpeed * frenzySpeed); }
        boss.setMaximumNoDamageTicks(0);   // a crowd can all land their hits; scaling keeps it fair
        boss.setHealth(Math.max(1, Math.min(REAL_HP, health)));
        move = Move.NONE;
    }

    private static void set(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** On top of the dais (the arena point is the floor level of the hall; the dais is a block higher). */
    private Location dais() { return zones.arenaCentre().add(0, 1.05, 0); }

    /** Health he'd need to last as long with this many fighters, and the damage factor that gives it. */
    private double effectiveHp() { return hpBase + hpPerFighter * crowd; }

    // ---------------------------------------------------------------- every half second: control, phases, the bar

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
            if (boss != null && boss.getHealth() <= 0) return;
            if (c.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
                Bukkit.broadcast(Component.text("Moloch can't exist on Peaceful difficulty. The awakening is called off; set /difficulty normal and try again.", NamedTextColor.RED));
                abort();
                return;
            }
            if (++respawns > 20) {
                plugin.getLogger().warning("Moloch keeps vanishing straight after he spawns (another plugin removing mobs, or mob limits?). Stopping the fight.");
                Bukkit.broadcast(Component.text("Moloch couldn't hold his form. The awakening is called off.", NamedTextColor.RED));
                abort();
                return;
            }
            if (collapsing) endCollapse();
            spawnBoss(savedHp);   // he dug away or was unloaded: he rises again as he was
            fx(Particle.LAVA, c, 40, 2, 0, null);
        }
        savedHp = boss.getHealth();
        if (half % 40 == 0) respawns = 0;

        // live scaling: every half second, count who's actually fighting in the hall
        List<Player> fighters = fighters();
        crowd = Math.max(1, fighters.size());
        hpScale = REAL_HP / effectiveHp();

        // how much of the recent damage came from bows: it fades, so the ward reacts to what's happening now
        projDmg *= 0.93; allDmg *= 0.93;

        double ratio = boss.getHealth() / REAL_HP;
        bar.progress((float) Math.max(0, Math.min(1, ratio)));
        updateBar(left);
        if (collapsing || emerging) return;

        // champions gone: his shield breaks
        if (shieldUp && championsLeft() == 0) {
            shieldUp = false;
            playNear(c, "minecraft:item.shield.break", 2f, 0.6f);
            playNear(c, "minecraft:block.bell.use", 2f, 0.5f);
            titleNear(c, Component.empty(), Component.text("His champions are dead. Moloch's guard is broken!", NamedTextColor.GOLD));
        }

        // he's too big for the doorways and his body is wider than his hitbox, so near the walls he'd look stuck in them.
        // If he's been pressed against a wall, or hasn't moved while people are out of reach, he leaps back into the hall.
        if (escapeCooldown > 0) escapeCooldown--;
        if (move == Move.NONE && !fighters.isEmpty()) {
            Location here = boss.getLocation();
            double dc = Math.max(Math.abs(here.getX() - c.getX()), Math.abs(here.getZ() - c.getZ()));
            edgeHalves = dc > 28 ? edgeHalves + 1 : 0;
            boolean still = lastSpot != null && lastSpot.getWorld() == here.getWorld() && lastSpot.distanceSquared(here) < 0.3 * 0.3;
            Player near = fighters.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(here))).get();
            stuckHalves = still && near.getLocation().distance(here) > 0.7 * scale + 3 ? stuckHalves + 1 : 0;
            lastSpot = here;
            if (escapeCooldown == 0 && (edgeHalves >= 3 || stuckHalves >= 6)) {
                edgeHalves = stuckHalves = 0;
                escapeCooldown = 12;
                Location to = near.getLocation();
                double nd = Math.max(Math.abs(to.getX() - c.getX()), Math.abs(to.getZ() - c.getZ()));
                if (nd > 22) to = c.clone().add(to.clone().subtract(c).toVector().multiply(0.5));   // they're by the wall too: land between them and the middle
                startLeapTo(to, near);
                return;
            }
        }

        // he's too big to leave the hall, so he roams it freely. Only if something has gone badly wrong
        // (fallen out of the world, or somehow far outside) is he put back on his dais.
        Location b = boss.getLocation();
        if (move == Move.NONE && (Math.max(Math.abs(b.getX() - c.getX()), Math.abs(b.getZ() - c.getZ())) > leashRadius || b.getY() < c.getY() - 20))
            boss.teleport(dais());

        // players only: forget anything else he's angry at, and hunt the nearest player
        for (int i = 0; i < 8; i++) {
            Entity angry = boss.getEntityAngryAt();
            if (angry == null || angry instanceof Player) break;
            boss.clearAnger(angry);
        }
        if (boss.getTarget() != null && !(boss.getTarget() instanceof Player)) boss.setTarget(null);
        if (!fighters.isEmpty()) {
            Player near = fighters.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).get();
            boss.setAnger(near, 100);
        }

        // the turning points of the fight
        int now = ratio > 0.75 ? 1 : ratio > 0.5 ? 2 : ratio > collapseAt ? 3 : 4;
        if (now > phase) phase = now;
        if (!championsCalled && ratio <= 0.75) summonChampions();
        if (!screamed && screamAt > 0 && ratio <= screamAt) scream();
        if (!collapsed && collapseAt > 0 && ratio <= collapseAt) { startCollapse(); return; }
        if (collapsed && !frenzy) startFrenzy();

        // drums, quicker each phase
        if (half % 29 == 1) playNear(c, "moloch:drums", 1f, 1f + 0.08f * (phase - 1));

        // fire rain once he's below half
        int rainEvery = frenzy ? 4 : 6;
        if (phase >= 3 && half % rainEvery == 0) for (Player p : fighters) {
            Location from = p.getLocation().add(rnd.nextDouble() * 6 - 3, 14, rnd.nextDouble() * 6 - 3);
            p.getWorld().spawn(from, SmallFireball.class, f -> {
                f.setDirection(new Vector(0, -1, 0));
                f.setIsIncendiary(false);
                f.setShooter(boss);
            });
        }
    }

    private void updateBar(long left) {
        String state;
        NamedTextColor col = NamedTextColor.DARK_RED;
        BossBar.Color bc = BossBar.Color.RED;
        if (emerging) { state = "RISING"; col = NamedTextColor.DARK_RED; }
        else if (collapsing) { state = "FEEDING"; col = NamedTextColor.DARK_PURPLE; bc = BossBar.Color.PURPLE; }
        else if (move == Move.STUN) { state = "STUNNED · STRIKE NOW"; col = NamedTextColor.GOLD; bc = BossBar.Color.YELLOW; }
        else if (shieldUp) { state = "shielded by " + champions.size() + " champion" + (champions.size() == 1 ? "" : "s"); col = NamedTextColor.GOLD; bc = BossBar.Color.YELLOW; }
        else if (wardLeft > 0) { state = "SOULFIRE WARD · arrows burn"; col = NamedTextColor.AQUA; bc = BossBar.Color.BLUE; }
        else if (frenzy) state = "FRENZY";
        else state = crowd + " fighter" + (crowd == 1 ? "" : "s");
        bar.color(bc);
        bar.name(Component.text("Moloch  ·  " + state + "  ·  " + CurseManager.clock((int) left), col, TextDecoration.BOLD));
    }

    // ---------------------------------------------------------------- every tick: his moves and his ward

    private void tickFast() {
        if (!active || stage != Stage.BOSS || dying || boss == null || !boss.isValid()) return;
        fastTicks++;
        if (collapsing || emerging) return;
        tickWard();
        if (shieldUp && fastTicks % 10 == 0) tethers();
        trackCampers();

        switch (move) {
            case NONE -> {
                Player camper = camper();
                if (camper != null && nextMoveIn < 60) { startChain(camper); break; }
                if (--nextMoveIn <= 0) pickMove();
            }
            case RING -> tickRing();
            case CHARGE -> tickCharge();
            case LEAP -> tickLeap();
            case BREATH -> tickBreath();
            case CHAIN -> tickChain();
            case GRAB -> tickGrab();
            case STUN -> tickStun();
        }
    }

    private void pickMove() {
        List<Player> f = fighters();
        if (f.isEmpty()) { nextMoveIn = 20; return; }
        Map<Move, Integer> w = new HashMap<>();
        w.put(Move.CHARGE, 3);
        w.put(Move.RING, 2);
        if (phase >= 2) { w.put(Move.LEAP, 3); w.put(Move.BREATH, 3); }
        Player far = farthest(f);
        if (far != null && far.getLocation().distance(boss.getLocation()) > chainRange) w.put(Move.CHAIN, 3);
        Player close = nearest(f);
        if (close != null && close.getLocation().distance(boss.getLocation()) <= grabReach()) w.put(Move.GRAB, 3);
        w.remove(lastMove);   // never the same move twice in a row
        int total = w.values().stream().mapToInt(Integer::intValue).sum(), roll = rnd.nextInt(Math.max(1, total));
        Move pick = Move.CHARGE;
        for (Map.Entry<Move, Integer> e : w.entrySet()) { roll -= e.getValue(); if (roll < 0) { pick = e.getKey(); break; } }
        Player any = f.get(rnd.nextInt(f.size()));
        switch (pick) {
            case RING -> startRing();
            case CHARGE -> startCharge(any);
            case LEAP -> startLeap(far != null && rnd.nextBoolean() ? far : any);
            case BREATH -> startBreath(any);
            case CHAIN -> startChain(far);
            case GRAB -> startGrab(close);
            default -> startCharge(any);
        }
    }

    /** Begin a move: he stops in his tracks (his body still has physics), facing his victim. */
    private void beginMove(Move m, Player target) {
        move = m;
        lastMove = m;
        moveTick = 0;
        victim = target;
        moveHit.clear();
        boss.setAware(false);
        boss.setVelocity(new Vector(0, boss.getVelocity().getY(), 0));
        if (target != null) face(target.getLocation());
    }

    private void endMove() {
        boolean wasStun = move == Move.STUN;
        if (move == Move.GRAB) letGo(null);
        move = Move.NONE;
        victim = null;
        if (boss != null && boss.isValid()) {
            if (wasStun) boss.setPose(Pose.STANDING, false);
            boss.setAware(true);
        }
        double crowdFactor = Math.max(0.6, 1 / (1 + 0.05 * (crowd - 1)));
        double phaseFactor = frenzy ? 0.6 : phase >= 3 ? 0.85 : 1.0;
        nextMoveIn = (int) Math.max(40, moveGap * 20 * crowdFactor * phaseFactor);
    }

    private void face(Location at) {
        Vector d = at.toVector().subtract(boss.getLocation().toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        boss.setRotation(yaw, 0);
    }

    // ---- the grab: he reaches for whoever is closest, hoists them up onto his head and squeezes. If the others hit him
    //      hard enough he drops them; otherwise he hurls them across the hall.

    private double grabReach() { return 0.7 * scale + 4; }

    private Player nearest(List<Player> f) {
        return f.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).orElse(null);
    }

    private void startGrab(Player target) {
        if (target == null) { endMove(); return; }
        beginMove(Move.GRAB, target);
        grabDamage = 0;
        Location at = boss.getLocation();
        at.getWorld().playSound(at, "minecraft:entity.warden.angry", 3f, 0.6f);
        at.getWorld().playSound(at, "minecraft:entity.ravager.attack", 3f, 0.5f);
        target.sendActionBar(Component.text("Moloch reaches for you! Get away!", NamedTextColor.RED, TextDecoration.BOLD));
    }

    private void tickGrab() {
        moveTick++;
        if (victim == null || !victim.isValid() || victim.isDead() || !victim.isOnline()) { endMove(); return; }
        Location at = boss.getLocation();
        if (moveTick <= 12) {   // the reach: a moment to dodge
            face(victim.getLocation());
            if (moveTick % 3 == 0) fx(Particle.DUST, victim.getLocation().add(0, 1, 0), 6, 0.4, 0, WARN);
            if (moveTick < 12) return;
            if (victim.getLocation().distance(at) > grabReach() + 1.5) {   // dodged
                at.getWorld().playSound(at, "minecraft:entity.player.attack.sweep", 3f, 0.5f);
                endMove();
                return;
            }
            boss.addPassenger(victim);
            at.getWorld().playSound(at, "minecraft:entity.iron_golem.attack", 3f, 0.5f);
            victim.showTitle(Title.title(Component.empty(), Component.text("Moloch has you!", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))));
            for (Player p : fighters()) if (p != victim)
                p.sendActionBar(Component.text("Moloch has grabbed " + victim.getName() + "! Hit him to make him let go!", NamedTextColor.GOLD));
            return;
        }
        if (!boss.getPassengers().contains(victim)) { endMove(); return; }
        // squeezing
        if ((moveTick - 12) % 20 == 0) {
            victim.damage(grabSqueeze, boss);
            victim.getWorld().playSound(victim.getLocation(), "minecraft:entity.player.hurt_sweet_berry_bush", 1.5f, 0.6f);
            fx(Particle.BLOCK, victim.getLocation(), 12, 0.3, 0.05, Material.REDSTONE_BLOCK.createBlockData());
        }
        double need = grabBreak * Math.max(1, crowd);
        if (grabDamage >= need) {   // the others hit him hard enough: he drops them
            Player freed = victim;
            letGo(new Vector(0, 0.3, 0));
            at.getWorld().playSound(at, "minecraft:entity.warden.hurt", 3f, 0.6f);
            sayNear(at, freed.getName() + " is torn free of Moloch's grip!", NamedTextColor.GOLD);
            move = Move.NONE;
            endMove();
            nextMoveIn += 40;   // he staggers
            return;
        }
        if (moveTick >= 12 + grabSeconds * 20) {   // the throw
            Player thrown = victim;
            Vector dir = at.getDirection().setY(0);
            if (dir.lengthSquared() < 1e-3) dir = new Vector(1, 0, 0);
            dir.normalize().rotateAroundY(Math.toRadians(rnd.nextInt(91) - 45));
            letGo(dir.multiply(1.9).setY(0.55));
            at.getWorld().playSound(at, "minecraft:entity.warden.roar", 3f, 0.9f);
            at.getWorld().playSound(at, "minecraft:entity.player.attack.knockback", 3f, 0.5f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (thrown.isValid() && !thrown.isDead()) { thrown.damage(grabThrowDamage, boss); if (thrown instanceof Player tp) plugin.cracks().crack(tp); } }, 2L);
            sayNear(at, "Moloch hurls " + thrown.getName() + " across the hall!", NamedTextColor.RED);
            endMove();
        }
    }

    /** Let go of whoever he's holding, flinging them with this velocity (next tick, once they're off him). */
    private void letGo(Vector fling) {
        Player held = victim;
        if (held == null || boss == null) return;
        releasing = true;
        try { boss.removePassenger(held); } finally { releasing = false; }
        if (fling != null) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (held.isValid()) held.setVelocity(fling); }, 1L);
    }

    /** The held player can't climb down off him. */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (!releasing && move == Move.GRAB && isBoss(e.getDismounted()) && e.getEntity() == victim) e.setCancelled(true);
    }

    // ---- the fire ring: a roar, a warning ring of smoke, then a blast around his feet

    private void startRing() {
        beginMove(Move.RING, null);
        boss.getWorld().playSound(boss.getLocation(), "minecraft:entity.warden.roar", 3f, 0.8f);
    }

    private void tickRing() {
        moveTick++;
        Location at = boss.getLocation();
        if (moveTick < 40) {
            if (moveTick % 8 == 0) ring(at, Particle.SMOKE, ringRadius);
            return;
        }
        ring(at, Particle.FLAME, ringRadius);
        ring(at, Particle.FLAME, ringRadius * 0.6);
        at.getWorld().playSound(at, "minecraft:entity.generic.explode", 2f, 0.6f);
        for (Player p : fighters()) {
            Vector d = p.getLocation().toVector().subtract(at.toVector()); d.setY(0);
            if (d.length() > ringRadius) continue;
            p.damage(ringDamage, boss);
            p.setFireTicks(60);
            if (d.lengthSquared() > 1e-4) p.setVelocity(d.normalize().multiply(1.2).setY(0.5));
        }
        endMove();
    }

    // ---- the bull charge: he scrapes the floor, shows his line, then thunders straight down it.
    //      Anything in the way is trampled. Dodge, and if he slams into a pillar or wall he topples over, stunned.

    private void startCharge(Player target) {
        beginMove(Move.CHARGE, target);
        Vector d = target.getLocation().toVector().subtract(boss.getLocation().toVector()); d.setY(0);
        moveDir = d.lengthSquared() < 1e-3 ? new Vector(1, 0, 0) : d.normalize();
        Location at = boss.getLocation();
        at.getWorld().playSound(at, "minecraft:entity.ravager.roar", 3f, 0.6f);
        at.getWorld().playSound(at, "minecraft:entity.warden.angry", 3f, 0.7f);
    }

    private void tickCharge() {
        moveTick++;
        Location at = boss.getLocation();
        if (moveTick <= 30) {   // the warning: hooves scraping, and his path marked on the floor
            if (moveTick % 4 == 0) for (double r = 2; r < 28; r += 1.0)
                fx(Particle.DUST, at.clone().add(moveDir.clone().multiply(r)).add(0, 0.25, 0), 1, 0.05, 0, WARN);
            if (moveTick % 8 == 0) {
                at.getWorld().playSound(at, "minecraft:entity.ravager.step", 2f, 0.5f);
                fx(Particle.BLOCK, at.clone().add(0, 0.2, 0), 20, 1.2, 0.1, Material.POLISHED_BLACKSTONE.createBlockData());
            }
            if (moveTick == 30) lastPos = at.clone();
            return;
        }
        // charging
        if (moveTick > 33) {
            double moved = Math.hypot(at.getX() - lastPos.getX(), at.getZ() - lastPos.getZ());
            Location c = zones.arenaCentre();
            boolean atWall = Math.max(Math.abs(at.getX() - c.getX()), Math.abs(at.getZ() - c.getZ())) > 29;
            if (moved < 0.25 || atWall) { startStun(); return; }
        }
        lastPos = at.clone();
        boss.setVelocity(moveDir.clone().multiply(0.85).setY(Math.min(0, boss.getVelocity().getY())));
        face(at.clone().add(moveDir));
        if (moveTick % 2 == 0) {
            fx(Particle.CLOUD, at.clone().add(0, 0.3, 0), 6, 1.2, 0.02, null);
            fx(Particle.BLOCK, at.clone().add(0, 0.2, 0), 10, 1.0, 0.1, Material.POLISHED_BLACKSTONE.createBlockData());
        }
        if (moveTick % 6 == 0) at.getWorld().playSound(at, "minecraft:entity.ravager.step", 3f, 0.6f);
        double reach = 0.7 * scale + 0.7, height = 2.9 * scale;
        for (Player p : fighters()) {
            if (moveHit.contains(p.getUniqueId())) continue;
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - at.getX(), pl.getZ() - at.getZ()) > reach || pl.getY() < at.getY() - 1 || pl.getY() > at.getY() + height) continue;
            moveHit.add(p.getUniqueId());
            p.damage(chargeDamage, boss);
            plugin.cracks().crack(p);
            Vector side = new Vector(-moveDir.getZ(), 0, moveDir.getX());
            if (side.dot(pl.toVector().subtract(at.toVector())) < 0) side.multiply(-1);
            p.setVelocity(side.multiply(1.2).add(moveDir.clone().multiply(0.8)).setY(0.7));
            p.getWorld().playSound(pl, "minecraft:entity.player.attack.knockback", 2f, 0.6f);
        }
        if (moveTick >= 30 + 45) { boss.setVelocity(new Vector(0, 0, 0)); endMove(); }
    }

    // ---- stunned: he crashes over onto his side, dazed. Everyone's hits do double damage.

    private void startStun() {
        move = Move.STUN;
        moveTick = 0;
        boss.setVelocity(new Vector(0, 0, 0));
        wardLeft = 0;   // the blow knocks his ward out too
        Location at = boss.getLocation();
        World w = at.getWorld();
        float yaw = Math.round(at.getYaw() / 90f) * 90f;   // lie square to the hall, so his body is easy to hit
        boss.setRotation(yaw, 0);
        boss.setPose(Pose.SLEEPING, true);
        w.playSound(at, "minecraft:block.anvil.land", 3f, 0.5f);
        w.playSound(at, "minecraft:entity.generic.explode", 3f, 0.7f);
        w.playSound(at, "minecraft:entity.ravager.stunned", 3f, 0.6f);
        fx(Particle.BLOCK, at.clone().add(moveDir.clone().multiply(2)).add(0, 2, 0), 200, 2, 0.2, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
        fx(Particle.CLOUD, at.clone().add(0, 0.5, 0), 60, 2.5, 0.05, null);
        for (Player p : fighters()) p.showTitle(Title.title(Component.empty(), Component.text("MOLOCH IS DOWN! Strike him!", NamedTextColor.GOLD, TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(400))));
    }

    private void tickStun() {
        moveTick++;
        Location at = boss.getLocation();
        if (moveTick % 4 == 0) {   // stars circling his head
            double a = moveTick * 0.35;
            for (int i = 0; i < 4; i++) {
                double ang = a + i * Math.PI / 2;
                fx(Particle.ENCHANTED_HIT, at.clone().add(Math.cos(ang) * 2.2, 2.6, Math.sin(ang) * 2.2), 2, 0.1, 0, null);
            }
        }
        if (moveTick >= stunSeconds * 20) {
            at.getWorld().playSound(at, "minecraft:entity.warden.roar", 3f, 0.7f);
            endMove();
        }
    }

    /** His lying body, as a cross of two boxes (he lies along one of them; this way it never matters which). */
    private boolean hitsLyingBody(Player p) {
        Location at = boss.getLocation();
        double len = 2.9 * scale, half = 0.45 * scale + 0.3, h = 0.9 * scale + 0.3;
        BoundingBox a = new BoundingBox(at.getX() - len, at.getY(), at.getZ() - half, at.getX() + len, at.getY() + h, at.getZ() + half);
        BoundingBox b = new BoundingBox(at.getX() - half, at.getY(), at.getZ() - len, at.getX() + half, at.getY() + h, at.getZ() + len);
        Location eye = p.getEyeLocation();
        Vector o = eye.toVector(), d = eye.getDirection();
        return a.rayTrace(o, d, 5.0) != null || b.rayTrace(o, d, 5.0) != null;
    }

    // ---- the leap: he crouches while a ring marks where he'll land, launches himself across the hall and slams down

    private void startLeap(Player target) {
        beginMove(Move.LEAP, target);
        moveTarget = clampToHall(target.getLocation());
        boss.getWorld().playSound(boss.getLocation(), "minecraft:entity.warden.sonic_charge", 3f, 0.6f);
    }

    /** A leap to a fixed spot (used to get himself out from against a wall). The ring doesn't follow anyone. */
    private void startLeapTo(Location spot, Player faceTo) {
        beginMove(Move.LEAP, null);
        moveTarget = clampToHall(spot);
        if (faceTo != null) face(faceTo.getLocation());
        boss.getWorld().playSound(boss.getLocation(), "minecraft:entity.warden.sonic_charge", 3f, 0.6f);
    }

    private void tickLeap() {
        moveTick++;
        Location at = boss.getLocation();
        if (moveTick <= 24) {
            if (moveTick <= 14 && victim != null && victim.isValid()) moveTarget = clampToHall(victim.getLocation());
            if (moveTick % 3 == 0) ring(moveTarget, Particle.DUST, slamRadius, WARN);
            if (moveTick % 8 == 0) fx(Particle.BLOCK, at.clone().add(0, 0.2, 0), 20, 1.2, 0.1, Material.POLISHED_BLACKSTONE.createBlockData());
            if (moveTick == 24) launch(at, moveTarget);
            return;
        }
        // the first tick of a jump is slowed by ground friction: put the arc back on course once he's airborne
        if (moveTick == 25 && leapV != null) boss.setVelocity(new Vector(leapV.getX() * 0.91, (leapV.getY() - 0.08) * 0.98, leapV.getZ() * 0.91));
        if (moveTick % 2 == 0) fx(Particle.CLOUD, at.clone().add(0, 1, 0), 3, 0.8, 0.01, null);
        if ((moveTick > 30 && boss.isOnGround()) || moveTick > 24 + 70) {
            slam(boss.getLocation());
            endMove();
        }
    }

    /** Throw him on a ballistic arc that lands on the target, worked out with the game's own air physics. */
    private void launch(Location from, Location to) {
        int t = 22;
        double geo = 0, k = 1;
        for (int i = 0; i < t; i++) { geo += k; k *= 0.91; }          // horizontal: speed x 0.91 each tick
        double s = 0, c = 0, vy = 0, y = 0, kk = 1;
        for (int i = 0; i < t; i++) { s += kk; kk *= 0.98; }            // vertical: (v - 0.08) x 0.98 each tick
        for (int i = 0; i < t; i++) { y += vy; vy = (vy - 0.08) * 0.98; }
        c = y;
        Vector d = to.toVector().subtract(from.toVector());
        Vector v = new Vector(d.getX() / geo, (d.getY() - c) / s, d.getZ() / geo);
        leapV = v.clone();
        boss.setVelocity(v);
        from.getWorld().playSound(from, "minecraft:entity.ravager.roar", 3f, 0.5f);
        fx(Particle.BLOCK, from.clone().add(0, 0.2, 0), 120, 2, 0.2, Material.POLISHED_BLACKSTONE.createBlockData());
    }

    private void slam(Location at) {
        World w = at.getWorld();
        w.playSound(at, "minecraft:entity.generic.explode", 3f, 0.5f);
        w.playSound(at, "minecraft:block.anvil.land", 3f, 0.4f);
        fx(Particle.BLOCK, at.clone().add(0, 0.3, 0), 300, slamRadius * 0.6, 0.25, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
        fx(Particle.CLOUD, at.clone().add(0, 0.5, 0), 80, slamRadius * 0.5, 0.08, null);
        for (int i = 0; i < 6; i++) fx(Particle.EXPLOSION, at.clone().add(rnd.nextGaussian() * 2, 0.5, rnd.nextGaussian() * 2), 1, 0, 0, null);
        for (Player p : fighters()) {
            Vector d = p.getLocation().toVector().subtract(at.toVector());
            double dist = Math.hypot(d.getX(), d.getZ());
            if (dist > slamRadius * 2 || Math.abs(d.getY()) > 6) continue;
            Vector out = new Vector(d.getX(), 0, d.getZ());
            out = out.lengthSquared() < 1e-3 ? new Vector(0, 0, 0) : out.normalize();
            if (dist <= slamRadius) {
                p.damage(slamDamage * (1 - 0.5 * dist / slamRadius), boss);
                p.setVelocity(out.multiply(0.8).setY(1.0));
                if (dist <= slamRadius * 0.6) plugin.cracks().crack(p);   // only those close to the impact
            } else p.setVelocity(out.multiply(0.4).setY(0.45));   // the floor jolts further out
        }
    }

    private Location clampToHall(Location l) {
        Location c = zones.arenaCentre(), out = l.clone();
        out.setX(Math.max(c.getX() - 26, Math.min(c.getX() + 26, out.getX())));
        out.setZ(Math.max(c.getZ() - 26, Math.min(c.getZ() + 26, out.getZ())));
        out.setY(c.getY() - 1);   // the hall floor
        return out;
    }

    // ---- furnace breath: the furnace in his belly roars up and he breathes a cone of fire, sweeping after his victim

    private void startBreath(Player target) {
        beginMove(Move.BREATH, target);
        Vector d = target.getEyeLocation().toVector().subtract(mouth().toVector());
        moveDir = d.lengthSquared() < 1e-3 ? new Vector(1, 0, 0) : d.normalize();
        playNear(boss.getLocation(), "moloch:furnace", 2f, 1.1f);
    }

    private Location mouth() { return boss.getLocation().add(0, 2.9 * scale * 0.8, 0); }

    private void tickBreath() {
        moveTick++;
        Location m = mouth();
        if (moveTick <= 20) {
            if (moveTick % 3 == 0) fx(Particle.LARGE_SMOKE, m, 6, 0.6, 0.03, null);
            return;
        }
        // sweep slowly after the victim
        if (victim != null && victim.isValid()) {
            Vector want = victim.getEyeLocation().toVector().subtract(m.toVector());
            if (want.lengthSquared() > 1e-3) {
                want.normalize();
                moveDir = moveDir.clone().multiply(0.94).add(want.multiply(0.06)).normalize();
            }
        }
        face(m.clone().add(moveDir));
        Location start = m.clone().add(moveDir.clone().multiply(1.5));
        for (int i = 0; i < 14; i++) {   // flames fired down the cone
            Vector v = moveDir.clone().add(new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).multiply(0.16)).normalize();
            start.getWorld().spawnParticle(Particle.FLAME, start, 0, v.getX(), v.getY(), v.getZ(), 0.55 + rnd.nextDouble() * 0.35, null, true);
        }
        if (moveTick % 3 == 0) {
            Vector v = moveDir.clone().add(new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).multiply(0.2));
            start.getWorld().spawnParticle(Particle.LARGE_SMOKE, start, 0, v.getX(), v.getY(), v.getZ(), 0.4, null, true);
        }
        if (moveTick % 6 == 0) start.getWorld().playSound(start, "minecraft:entity.blaze.shoot", 2f, 0.5f);
        if (moveTick % 10 == 0) for (Player p : fighters()) {
            Vector to = p.getEyeLocation().toVector().subtract(m.toVector());
            double dist = to.length();
            if (dist > breathRange || dist < 0.1) continue;
            if (to.normalize().dot(moveDir) < Math.cos(Math.toRadians(25))) continue;
            p.damage(breathDamage, boss);
            p.setFireTicks(Math.max(p.getFireTicks(), 80));
        }
        if (moveTick >= 20 + 50) endMove();
    }

    // ---- the chain pull: anyone who keeps their distance is hooked and dragged to his feet

    private void startChain(Player target) {
        if (target == null) { endMove(); return; }
        beginMove(Move.CHAIN, target);
        Location at = boss.getLocation();
        at.getWorld().playSound(at, "minecraft:block.chain.place", 3f, 0.5f);
        at.getWorld().playSound(at, "minecraft:entity.fishing_bobber.throw", 3f, 0.4f);
        target.sendActionBar(Component.text("Moloch's chain is coming for you!", NamedTextColor.RED));
    }

    private void tickChain() {
        moveTick++;
        if (victim == null || !victim.isValid() || victim.isDead() || !zones.inArena(victim.getLocation())) { endMove(); return; }
        Location from = boss.getLocation().add(0, 2.9 * scale * 0.6, 0), to = victim.getLocation().add(0, 1, 0);
        Vector d = to.toVector().subtract(from.toVector());
        double dist = d.length();
        face(victim.getLocation());
        if (moveTick % 2 == 0) {   // the chain snaking out to them
            double reach = Math.min(dist, dist * moveTick / 16.0);
            Vector u = d.clone().normalize();
            for (double r = 1; r < reach; r += 0.6) fx(Particle.DUST, from.clone().add(u.clone().multiply(r)), 1, 0.02, 0, CHAIN);
        }
        if (moveTick == 16) {
            Vector pull = from.toVector().subtract(to.toVector());
            double strength = Math.min(2.4, 0.6 + dist * 0.07);
            victim.setVelocity(pull.normalize().multiply(strength).setY(0.45));
            victim.damage(3, boss);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, false, true));
            victim.getWorld().playSound(victim.getLocation(), "minecraft:entity.fishing_bobber.retrieve", 3f, 0.5f);
            victim.getWorld().playSound(victim.getLocation(), "minecraft:block.chain.break", 3f, 0.5f);
            victim.sendActionBar(Component.text("Moloch drags you to him!", NamedTextColor.DARK_RED));
            farTicks.remove(victim.getUniqueId());
        }
        if (moveTick >= 22) endMove();
    }

    /** Who's been hanging back out of his reach the longest (10+ seconds), if anyone. */
    private Player camper() {
        UUID worst = null; int most = 200;
        for (Map.Entry<UUID, Integer> e : farTicks.entrySet()) if (e.getValue() >= most) { most = e.getValue(); worst = e.getKey(); }
        Player p = worst == null ? null : Bukkit.getPlayer(worst);
        return p != null && zones.inArena(p.getLocation()) ? p : null;
    }

    private void trackCampers() {
        if (fastTicks % 10 != 0) return;
        Set<UUID> seen = new HashSet<>();
        for (Player p : fighters()) {
            seen.add(p.getUniqueId());
            if (p.getLocation().distance(boss.getLocation()) > chainRange + 2) farTicks.merge(p.getUniqueId(), 10, Integer::sum);
            else farTicks.remove(p.getUniqueId());
        }
        farTicks.keySet().retainAll(seen);
    }

    private Player farthest(List<Player> f) {
        return f.stream().max(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).orElse(null);
    }

    // ---- the Soulfire Ward: a shell of soul flame that burns arrows to ash and throws fire back at the archer

    private void tickWard() {
        if (wardCooldown > 0) wardCooldown--;
        if (wardLeft > 0) {
            if (--wardLeft == 0) playNear(boss.getLocation(), "minecraft:block.fire.extinguish", 2f, 0.6f);
            if (fastTicks % 3 == 0) {
                Location at = boss.getLocation();
                double r = 0.6 * scale + 1.2, h = 2.9 * scale;
                for (int ring = 0; ring < 3; ring++) for (int i = 0; i < 10; i++) {
                    double a = fastTicks * 0.15 * (ring % 2 == 0 ? 1 : -1) + i * Math.PI / 5;
                    fx(Particle.SOUL_FIRE_FLAME, at.clone().add(Math.cos(a) * r, h * (0.2 + 0.3 * ring), Math.sin(a) * r), 1, 0, 0, null);
                }
            }
            return;
        }
        if (move == Move.STUN) return;
        boolean archers = allDmg > 20 && projDmg / allDmg > 0.5;   // he notices when most of the damage is arrows
        if (--wardNext <= 0 || (archers && wardCooldown == 0)) raiseWard();
    }

    private void raiseWard() {
        wardLeft = wardSeconds * 20;
        wardNext = wardEvery * 20;
        wardCooldown = 20 * 20;
        Location at = boss.getLocation();
        at.getWorld().playSound(at, "minecraft:block.beacon.activate", 3f, 0.5f);
        at.getWorld().playSound(at, "minecraft:item.firecharge.use", 3f, 0.5f);
        for (Player p : fighters()) p.sendActionBar(Component.text("Moloch raises his Soulfire Ward: arrows burn to ash!", NamedTextColor.AQUA));
    }

    // ---------------------------------------------------------------- the turning points

    /** 75%: his golden champions march in through the doorways, and while any live he's shielded. */
    private void summonChampions() {
        championsCalled = true;
        Location c = zones.arenaCentre();
        World w = c.getWorld();
        int n = (int) Math.min(champMax, Math.round(champBase + champPerFighter * (crowd - 1)));
        int offset = rnd.nextInt(DOORS.length);
        for (int i = 0; i < n; i++) {
            Location at = doorSpot(c, offset + i * Math.max(1, DOORS.length / Math.max(1, n)));
            PiglinBrute b = w.spawn(at, PiglinBrute.class, pb -> {
                pb.setImmuneToZombification(true);
                EntityEquipment eq = pb.getEquipment();
                if (eq != null) {
                    eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    ItemStack chest = new ItemStack(Material.GOLDEN_CHESTPLATE);
                    chest.addUnsafeEnchantment(Enchantment.PROTECTION, 2);
                    eq.setChestplate(chest);
                    eq.setLeggings(new ItemStack(Material.GOLDEN_LEGGINGS));
                    eq.setBoots(new ItemStack(Material.GOLDEN_BOOTS));
                    ItemStack axe = new ItemStack(Material.GOLDEN_AXE);
                    axe.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
                    eq.setItemInMainHand(axe);
                    eq.setHelmetDropChance(0f); eq.setChestplateDropChance(0f); eq.setLeggingsDropChance(0f);
                    eq.setBootsDropChance(0f); eq.setItemInMainHandDropChance(0f);
                }
                set(pb, Attribute.MAX_HEALTH, champHealth);
                pb.setHealth(champHealth);
                pb.customName(Component.text("Champion of Moloch", NamedTextColor.GOLD, TextDecoration.BOLD));
                pb.setCustomNameVisible(true);
            });
            tagMinion(b);
            champions.add(b.getUniqueId());
            fx(Particle.DUST, at.clone().add(0, 1, 0), 50, 0.7, 0, GOLD);
            w.playSound(at, "minecraft:item.armor.equip_gold", 2f, 0.8f);
        }
        shieldUp = n > 0;
        playNear(c, "minecraft:block.bell.resonate", 3f, 0.6f);
        playNear(c, "minecraft:entity.piglin_brute.angry", 3f, 0.7f);
        titleNear(c, Component.text("THE CHAMPIONS OF MOLOCH", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("while they live, he is shielded", NamedTextColor.YELLOW));
        sayNear(c, "His gilded champions march in. Kill them to break his shield!", NamedTextColor.GOLD);
    }

    /** Golden threads from each champion to Moloch, so everyone can see what's shielding him. */
    private void tethers() {
        Location to = boss.getLocation().add(0, 2.9 * scale * 0.5, 0);
        for (UUID id : champions) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) continue;
            Location from = e.getLocation().add(0, 1.2, 0);
            Vector d = to.toVector().subtract(from.toVector());
            double len = d.length();
            d.normalize();
            for (double r = 0.8; r < len; r += 1.2) fx(Particle.DUST, from.clone().add(d.clone().multiply(r)), 1, 0.02, 0, GOLD);
        }
    }

    /** 50%: a scream that shakes the hall, leaving everyone reeling with Nausea and Weakness. */
    private void scream() {
        screamed = true;
        Location at = boss.getLocation(), head = at.clone().add(0, 2.9 * scale * 0.85, 0);
        World w = at.getWorld();
        w.playSound(head, "minecraft:entity.warden.roar", 4f, 0.55f);
        w.playSound(head, "minecraft:entity.ghast.scream", 4f, 0.5f);
        w.playSound(head, "minecraft:entity.ravager.roar", 4f, 0.6f);
        fx(Particle.SONIC_BOOM, head, 1, 0, 0, null);
        for (int ring = 1; ring <= 3; ring++) {
            final int r = ring;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int i = 0; i < 24; i++) {
                    double a = i * Math.PI * 2 / 24, d = r * 8;
                    fx(Particle.SONIC_BOOM, at.clone().add(Math.cos(a) * d, 1.5, Math.sin(a) * d), 1, 0, 0, null);
                }
            }, r * 3L);
        }
        for (Player p : fighters()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, screamNausea * 20, 0, false, false, true));
            if (screamWeakness > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, screamWeakness * 20, 0, false, false, true));
        }
        titleNear(at, Component.text("MOLOCH SCREAMS", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("the hall spins around you", NamedTextColor.GRAY));
    }

    /** 25%: he freezes and crashes to the floor, then lies there feeding: healing, and draining everyone's hunger to nothing. */
    private void startCollapse() {
        if (move != Move.NONE) endMove();
        collapsed = true;
        collapsing = true;
        collapseTicks = 0;
        collapseBoss = boss;
        wardLeft = 0;
        healPerTick = REAL_HP * collapseHeal / (collapseSeconds * 20.0);
        boss.setAI(false);
        boss.setInvulnerable(true);
        boss.setPose(Pose.SLEEPING, true);
        Location at = boss.getLocation();
        World w = at.getWorld();
        w.playSound(at, "minecraft:entity.generic.explode", 3f, 0.5f);
        w.playSound(at, "minecraft:entity.warden.death", 3f, 0.6f);
        w.playSound(at, "minecraft:block.anvil.land", 2f, 0.5f);
        fx(Particle.BLOCK, at.clone().add(0, 0.3, 0), 250, scale * 1.4, 0.15, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
        fx(Particle.CLOUD, at.clone().add(0, 0.5, 0), 80, scale * 1.2, 0.05, null);
        for (Player p : fighters()) if (p.getLocation().distanceSquared(at) < 16 * 16) p.setVelocity(p.getVelocity().setY(0.45));
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
        boss.setHealth(Math.min(REAL_HP, boss.getHealth() + healPerTick));
        savedHp = boss.getHealth();
        Location core = boss.getLocation().add(0, 1.2, 0);
        int food = (int) Math.floor(20 * (1 - t));
        for (Player p : fighters()) {
            if (p.getFoodLevel() > food) p.setFoodLevel(food);
            p.setSaturation(0f);
            if (collapseTicks % 3 == 0) {
                Location eye = p.getEyeLocation();
                Vector to = core.toVector().subtract(eye.toVector());
                for (int i = 0; i < 3; i++) fx(Particle.SOUL, eye.clone().add(to.clone().multiply(rnd.nextDouble())), 1, 0.1, 0.01, null);
            }
        }
        if (collapseTicks % 4 == 0) fx(Particle.SCULK_SOUL, core, 4, scale * 0.8, 0.02, null);
        if (collapseTicks % 16 == 0) core.getWorld().playSound(core, "minecraft:entity.warden.heartbeat", 3f, 0.6f);
        if (collapseTicks >= total) endCollapse();
    }

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
        nextMoveIn = 60;
    }

    /** After the collapse: faster, and his moves come quicker. */
    private void startFrenzy() {
        frenzy = true;
        AttributeInstance sp = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(baseSpeed * frenzySpeed);
        sayNear(boss.getLocation(), "Moloch is in a FRENZY!", NamedTextColor.RED);
    }

    // ---------------------------------------------------------------- helpers for the fight

    /** Servants that wander out of the hall are sent back to a doorway. */
    private void keepMinionsIn(Location c) {
        if (half % 4 != 0) return;
        for (UUID id : new ArrayList<>(minions)) {
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) continue;
            Location l = e.getLocation();
            if (Math.max(Math.abs(l.getX() - c.getX()), Math.abs(l.getZ() - c.getZ())) > 33 || l.getY() < c.getY() - 6)
                e.teleport(doorSpot(c, rnd.nextInt(DOORS.length)));
        }
    }

    private void ring(Location at, Particle p, double radius) { ring(at, p, radius, null); }

    private void ring(Location at, Particle p, double radius, Object data) {
        int n = (int) Math.max(24, radius * 6);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            fx(p, at.clone().add(Math.cos(a) * radius, 0.3, Math.sin(a) * radius), data == null ? 2 : 1, 0.08, 0.01, data);
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

    /** Particles everyone in the hall can see, however far back they stand. */
    private void fx(Particle p, Location at, int count, double spread, double speed, Object data) {
        at.getWorld().spawnParticle(p, at, count, spread, spread, spread, speed, data, true);
    }

    // ---------------------------------------------------------------- his death: the idol breaks apart

    private static final int CRASH_TICK = 120, FINAL_TICK = 190;
    private static final Material[] WRECK = {Material.WAXED_COPPER_BLOCK, Material.WAXED_EXPOSED_COPPER, Material.WAXED_WEATHERED_COPPER,
            Material.GOLD_BLOCK, Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS, Material.IRON_BLOCK};
    static final String DEBRIS = "moloch_debris";

    private void startDeath(Player killer) {
        dying = true;
        deathTicks = 0;
        if (collapsing) endCollapse();
        if (move == Move.GRAB) letGo(null);
        move = Move.NONE;
        wardLeft = 0;
        Location c = zones.arenaCentre();
        boss.setPose(Pose.STANDING, false);
        boss.setAI(false);
        boss.setInvulnerable(true);
        boss.setSilent(true);
        boss.setHealth(1);
        for (UUID id : minions) {   // any servants left crumble with him
            Entity m = Bukkit.getEntity(id);
            if (m == null) continue;
            fx(Particle.POOF, m.getLocation().add(0, 1, 0), 12, 0.4, 0.02, null);
            m.remove();
        }
        minions.clear();
        champions.clear();
        bar.color(BossBar.Color.WHITE);
        bar.name(Component.text("Moloch is breaking apart", NamedTextColor.GOLD, TextDecoration.BOLD));
        bar.progress(0f);
        for (Player p : c.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(c) < 200 * 200) p.playSound(c, "moloch:death", 6f, 1f);
            else p.playSound(p.getLocation(), "moloch:death", 0.5f, 1f);
        }
        titleNear(c, Component.text("MOLOCH FALLS", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(killer != null ? "struck down by " + killer.getName() : "", NamedTextColor.YELLOW));
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDeath, 1L, 1L);
    }

    /**
     * Ten seconds, timed to his death sound: he freezes and shudders while cracks burst open and pieces of metal
     * break off him; at six seconds his legs give and he crashes over onto the floor; then he bursts apart.
     */
    private void tickDeath() {
        if (boss == null || !boss.isValid()) { finishDeath(); return; }
        deathTicks++;
        Location at = boss.getLocation();
        World w = at.getWorld();
        double h = 2.9 * scale;
        boolean down = deathTicks >= CRASH_TICK;

        if (!down) {
            // shuddering, harder as he gives way
            double k = deathTicks / (double) CRASH_TICK;
            if (deathTicks % 2 == 0) {
                Location j = at.clone();
                j.setYaw(at.getYaw() + (float) ((rnd.nextDouble() - 0.5) * (2 + 8 * k)));
                boss.teleport(j);
            }
            // cracks bursting open across his body
            if (rnd.nextDouble() < 0.06 + 0.22 * k) {
                Location crack = bodyPoint(at, h);
                fx(Particle.EXPLOSION, crack, 1, 0, 0, null);
                fx(Particle.CRIT, crack, 25, 0.3, 0.6, null);
                fx(Particle.LARGE_SMOKE, crack, 6, 0.3, 0.03, null);
                w.playSound(crack, rnd.nextBoolean() ? "minecraft:block.anvil.land" : "minecraft:entity.zombie.attack_iron_door", 2f, 0.5f + (float) rnd.nextDouble() * 0.4f);
                if (rnd.nextDouble() < 0.5 + 0.5 * k) throwDebris(crack, 1 + rnd.nextInt(2), 0.35);
            }
            if (deathTicks % 10 == 0) fx(Particle.LARGE_SMOKE, at.clone().add(0, h * 0.6, 0), 8, scale * 0.5, 0.02, null);
        }

        // the crash: his legs buckle and he topples over onto the floor
        if (deathTicks == CRASH_TICK) {
            boss.setRotation(Math.round(at.getYaw() / 90f) * 90f, 0);
            boss.setPose(Pose.SLEEPING, true);
            w.playSound(at, "minecraft:block.anvil.land", 4f, 0.4f);
            w.playSound(at, "minecraft:entity.generic.explode", 4f, 0.6f);
            w.playSound(at, "minecraft:entity.iron_golem.death", 4f, 0.5f);
            fx(Particle.BLOCK, at.clone().add(0, 0.4, 0), 400, scale * 1.6, 0.25, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
            fx(Particle.CLOUD, at.clone().add(0, 0.6, 0), 120, scale * 1.5, 0.08, null);
            for (int i = 0; i < 4; i++) fx(Particle.EXPLOSION, at.clone().add(rnd.nextGaussian() * scale, 0.8, rnd.nextGaussian() * scale), 1, 0, 0, null);
            throwDebris(at.clone().add(0, 1.5, 0), 10, 0.6);
            for (Player p : fighters()) {   // the whole floor jumps
                double d = p.getLocation().distance(at);
                if (d < 20) p.setVelocity(p.getVelocity().setY(0.55 * (1 - d / 25)));
            }
        }

        // on the floor: pieces keep bursting off him
        if (down && deathTicks < FINAL_TICK && rnd.nextDouble() < 0.25) {
            Location crack = at.clone().add(rnd.nextGaussian() * scale * 0.9, 0.6 + rnd.nextDouble() * scale * 0.6, rnd.nextGaussian() * scale * 0.9);
            fx(Particle.EXPLOSION, crack, 1, 0, 0, null);
            fx(Particle.CRIT, crack, 20, 0.3, 0.5, null);
            throwDebris(crack, 1, 0.45);
        }
        if (down && deathTicks % 6 == 0) fx(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 10, scale, 0.03, null);

        // the last burst: he breaks apart and the wreckage rains down
        if (deathTicks == FINAL_TICK) {
            Location core = at.clone().add(0, 1.5, 0);
            for (int i = 0; i < 3; i++) fx(Particle.EXPLOSION_EMITTER, core.clone().add(rnd.nextGaussian() * scale, 0.5, rnd.nextGaussian() * scale), 1, 0, 0, null);
            fx(Particle.BLOCK, core, 500, scale * 1.4, 0.4, Material.WAXED_COPPER_BLOCK.createBlockData());
            fx(Particle.BLOCK, core, 300, scale * 1.4, 0.4, Material.GOLD_BLOCK.createBlockData());
            fx(Particle.CRIT, core, 200, scale, 1.0, null);
            fx(Particle.LARGE_SMOKE, core, 150, scale * 1.5, 0.1, null);
            w.playSound(core, "minecraft:entity.generic.explode", 5f, 0.5f);
            w.playSound(core, "minecraft:block.anvil.destroy", 4f, 0.5f);
            throwDebris(core, 24, 0.9);
            if (deathXp > 0) dropXp(core, deathXp);
            for (Player p : fighters()) {
                Vector d = p.getLocation().toVector().subtract(at.toVector()); d.setY(0);
                if (d.length() > 16 || d.lengthSquared() < 1e-4) continue;
                p.setVelocity(d.normalize().multiply(0.9 * (1 - d.length() / 20)).setY(0.4));
            }
            boss.remove();
        }
        if (deathTicks >= DEATH_TICKS) finishDeath();
    }

    /** A random point on his standing body. */
    private Location bodyPoint(Location at, double h) {
        return at.clone().add(rnd.nextGaussian() * scale * 0.35, h * (0.15 + 0.8 * rnd.nextDouble()), rnd.nextGaussian() * scale * 0.35);
    }

    /** Chunks of metal and stone flung off him. They clang when they hit the floor and vanish (no blocks are placed). */
    private void throwDebris(Location from, int count, double power) {
        for (int i = 0; i < count; i++) {
            org.bukkit.entity.FallingBlock fb = from.getWorld().spawnFallingBlock(from, WRECK[rnd.nextInt(WRECK.length)].createBlockData());
            fb.setDropItem(false);
            fb.setHurtEntities(false);
            fb.setPersistent(false);
            fb.addScoreboardTag(DEBRIS);
            fb.setVelocity(new Vector(rnd.nextGaussian(), 0.6 + rnd.nextDouble(), rnd.nextGaussian()).normalize().multiply(power + rnd.nextDouble() * power));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDebrisLands(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(DEBRIS)) return;
        e.setCancelled(true);
        Location l = e.getBlock().getLocation().add(0.5, 0.2, 0.5);
        l.getWorld().playSound(l, rnd.nextBoolean() ? "minecraft:block.anvil.land" : "minecraft:entity.zombie.attack_iron_door", 1.2f, 0.7f + (float) rnd.nextDouble() * 0.6f);
        if (e.getEntity() instanceof org.bukkit.entity.FallingBlock fb)
            l.getWorld().spawnParticle(Particle.BLOCK, l, 15, 0.3, 0.1, 0.3, 0.05, fb.getBlockData(), true);
        e.getEntity().remove();
    }

    private void dropXp(Location at, int amount) {
        while (amount > 0) {
            int v = Math.min(amount, 50);
            amount -= v;
            at.getWorld().spawn(at, ExperienceOrb.class, o -> {
                o.setExperience(v);
                o.setVelocity(new Vector(rnd.nextGaussian() * 0.3, 0.3 + rnd.nextDouble() * 0.4, rnd.nextGaussian() * 0.3));
            });
        }
    }

    private void finishDeath() {
        if (deathTask != null) { deathTask.cancel(); deathTask = null; }
        if (boss != null && boss.isValid()) boss.remove();
        win();
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
        // his horns and regalia
        Set<UUID> fought = new HashSet<>();
        for (Map.Entry<UUID, Double> e : damage.entrySet()) if (e.getValue() >= minDamage) fought.add(e.getKey());
        plugin.gear().reward(top, fought);
    }

    private void lose() {
        Location c = zones.arenaCentre();
        if (boss != null && boss.isValid()) fx(Particle.LAVA, boss.getLocation(), 60, 2, 0, null);
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
        move = Move.NONE;
        wardLeft = 0;
        if (deathTask != null) { deathTask.cancel(); deathTask = null; }
        if (collapseTask != null) { collapseTask.cancel(); collapseTask = null; }
        collapsing = false;
        collapseBoss = null;
        if (boss != null && boss.isValid()) boss.remove();
        boss = null;
        for (UUID id : minions) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        minions.clear();
        champions.clear();
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

    /**
     * Every hit on him. Only players can hurt him. Then: his Soulfire Ward burns arrows and throws fire back,
     * arrows do half damage anyway, his champions shield him, he takes double while stunned, and the whole thing
     * is scaled to the crowd in the hall.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBossHurt(EntityDamageEvent e) {
        if (!isBoss(e.getEntity())) return;
        Player p = attacker(e);
        if (p == null) { e.setCancelled(true); return; }
        boolean projectile = e instanceof EntityDamageByEntityEvent ed && ed.getDamager() instanceof Projectile;
        if (projectile && wardLeft > 0) {
            e.setCancelled(true);
            Entity arrow = ((EntityDamageByEntityEvent) e).getDamager();
            fx(Particle.SOUL_FIRE_FLAME, arrow.getLocation(), 12, 0.3, 0.05, null);
            arrow.getWorld().playSound(arrow.getLocation(), "minecraft:block.fire.extinguish", 1.5f, 1.2f);
            arrow.remove();
            reflect(p);
            return;
        }
        // while he lies stunned, his real hitbox is tiny: hits come through the swing check below instead
        if (move == Move.STUN && !synthetic && !projectile) { e.setCancelled(true); return; }
        double m = hpScale;
        if (projectile) m *= projectileMultiplier;
        if (shieldUp) m *= championShield;
        if (move == Move.STUN) m *= stunMultiplier;
        e.setDamage(e.getDamage() * m);
        allDmg += e.getDamage() / hpScale;
        if (move == Move.GRAB && moveTick > 12) grabDamage += e.getDamage() / hpScale;
        if (projectile) projDmg += e.getDamage() / hpScale;
    }

    /** A fireball thrown back at an archer who shot into his ward. */
    private void reflect(Player at) {
        Location from = boss.getLocation().add(0, 2.9 * scale * 0.6, 0);
        Vector d = at.getEyeLocation().toVector().subtract(from.toVector());
        if (d.lengthSquared() < 1e-3) return;
        d.normalize();
        Location spawn = from.clone().add(d.clone().multiply(0.6 * scale + 1.5));
        boss.getWorld().spawn(spawn, SmallFireball.class, f -> {
            f.setDirection(d);
            f.setVelocity(d.clone().multiply(1.2));
            f.setIsIncendiary(false);
            f.setShooter(boss);
        });
    }

    /** While he's toppled over, a swing at his lying body lands (his real hitbox shrinks when he lies down). */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING || move != Move.STUN || boss == null || !boss.isValid()) return;
        Player p = e.getPlayer();
        if ((p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) || !zones.inArena(p.getLocation())) return;
        int last = lastSwing.getOrDefault(p.getUniqueId(), -100);
        if (fastTicks - last < 8 || !hitsLyingBody(p)) return;
        lastSwing.put(p.getUniqueId(), fastTicks);
        AttributeInstance a = p.getAttribute(Attribute.ATTACK_DAMAGE);
        double cd = p.getAttackCooldown();
        double dmg = (a != null ? a.getValue() : 1) * (0.2 + 0.8 * cd * cd);
        synthetic = true;
        try { boss.damage(dmg, p); } finally { synthetic = false; }
        fx(Particle.CRIT, p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(2.5)), 8, 0.3, 0.1, null);
    }

    /** The killing blow doesn't kill him outright: it starts his death. Nothing hurts him while he dies or feeds. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossLethal(EntityDamageEvent e) {
        if (!active || !isBoss(e.getEntity()) || !(e.getEntity() instanceof Warden w)) return;
        if (dying || collapsing || emerging) { e.setCancelled(true); return; }
        if (w.getHealth() - e.getFinalDamage() > 0) return;
        Player p = attacker(e);
        if (p != null) damage.merge(p.getUniqueId(), w.getHealth() / hpScale, Double::sum);
        e.setCancelled(true);
        startDeath(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossHurtRecord(EntityDamageEvent e) {
        if (!isBoss(e.getEntity())) return;
        Player p = attacker(e);
        if (p != null) damage.merge(p.getUniqueId(), e.getFinalDamage() / hpScale, Double::sum);
    }

    /** He only hurts players. His sonic boom is his fiery breath: it burns, but it's capped so it can't one-shot people. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHits(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity sh) src = sh;
        if (!isBoss(src)) return;
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
        if (active && !dying) win();
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
        // nothing spawns on its own in or on the pyramid (its plaza and moat too): only Moloch, his summons, and what players spawn
        if (templeNoSpawns && NATURAL_REASONS.contains(e.getSpawnReason().name()) && zones.inTemple(e.getLocation())) { e.setCancelled(true); return; }
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
        double hp = boss != null ? boss.getHealth() / hpScale : 0;
        return "AWAKE, " + Math.round(hp) + "/" + Math.round(effectiveHp()) + " health against " + crowd + " fighter" + (crowd == 1 ? "" : "s");
    }
}
