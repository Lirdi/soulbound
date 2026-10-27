package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.entity.BlockDisplay;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * God, in place of the ender dragon: a giant grey-bearded old man in white robes with a halo, drawn where the
 * (invisible) dragon is, with an old man's voice. His fight is the Seven Days of Creation:
 *
 *   1. Let there be light   seated on his throne, peaceful and chatty until someone strikes him; then he fights seated
 *   2. The firmament        he rises and swims through the sky; Rapture lifts players into the air
 *   3. The heavenly host    he calls down two Archangels (ghasts whose fireballs smite)
 *   4. Lights in heaven     falling stars, Pillar of Salt (look at him and you're frozen)
 *   5. Creatures of the sky angels (vexes) swoop in; Judgement marks Moloch's sinners
 *   6. In my own image      "Fine. FINE.": smite storms, he hurls his throne, "Get off my lawn!"
 *   7. He rested            he keeps dozing off (double damage) and wakes furious
 *
 * Smite (a pillar of light, then lightning) runs through every day. The dragon's own fight carries on underneath.
 */
final class GodManager implements Listener {
    static final String TAG = "soulbound_god", ANGEL = "soulbound_angel", RELIC = "soulbound_relic";
    private static final Particle.DustOptions HALO = new Particle.DustOptions(Color.fromRGB(255, 214, 90), 2.2f);
    private static final Particle.DustOptions BEAM = new Particle.DustOptions(Color.fromRGB(255, 236, 140), 2.4f);
    private static final Particle.DustOptions SALT = new Particle.DustOptions(Color.fromRGB(245, 245, 240), 1.6f);
    private static final String[] DAY_TITLES = {"", "THE FIRST DAY", "THE SECOND DAY", "THE THIRD DAY", "THE FOURTH DAY", "THE FIFTH DAY", "THE SIXTH DAY", "THE SEVENTH DAY"};
    private static final String[] DAY_LINES = {"",
            "Let there be light.",
            "Let there be a firmament. Up you go.",
            "Let the heavens send their host. Archangels! Attend me.",
            "Let there be lights in the heavens. Falling on you.",
            "Let the skies teem with creatures. Angels! Sic 'em.",
            "In my own image. And look how THAT turned out. Fine. FINE.",
            "And on the seventh day... I rested. Yawn."};

    /** His last words when he falls: he was going easy on you, obviously. */
    private static final String[][] LAST_WORDS = {
            {"I was going easy on you, you know.", "Next time I won't."},
            {"Alright. I let you have that one.", "Don't get used to it."},
            {"I could've ended you on the first day, you know.", "I just wanted to see you try."},
            {"I wasn't even trying.", "Honestly. Not even a little."},
            {"I was holding back. For your sake.", "You're welcome."},
            {"That was a warm-up, you know.", "Enjoy it while it lasts."},
            {"I went easy on you because you're new.", "Next time I bring the flood."},
            {"Fine. I let you win.", "Somebody had to."},
            {"I didn't want to embarrass you in front of your friends.", "So I let you have it."},
            {"I was only using one hand.", "And I'm left-handed."}};

    private final Soulbound plugin;
    private final Store store;
    private final Random rnd = new Random();
    private final Map<UUID, UUID> godOf = new HashMap<>();   // dragon -> its God figure
    private final Map<UUID, Vector> heading = new HashMap<>();
    private final Map<UUID, Location> lastAt = new HashMap<>();
    private BukkitTask task;
    private int tick;

    // settings
    private boolean enabled, flyingPose;
    private double scale, hitShare, flyBack, flyDown, standDown, sitOffset, smiteDamage, napWake, sinnerDays;
    private int napSeconds;
    private double hpBase, hpPerFighter, toughness = 1;   // toughness: the share of damage that gets through, for the crowd

    // the fight
    private UUID fightDragon;
    /** True while God is fighting (not holding court on his throne, not dead and gone). */
    boolean fighting() { return fightDragon != null && !peaceful; }
    private boolean peaceful = true, judged, lowSaid, deathSaid, napping, hadFighters, idleSaid;
    private int day, nextAbility = 160, napTicks, nextNap, saltTicks = -1, emptyTicks, nearTicks;
    private double napDamage;
    private long lastLine, lastHitLine;
    private ArmorStand seat;
    private TextDisplay snore;
    private final List<Mark> marks = new ArrayList<>();
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Map<UUID, Integer> frozenUntil = new HashMap<>(), saltImmune = new HashMap<>();
    private final Set<UUID> sinners = new HashSet<>(), greeted = new HashSet<>(), angels = new HashSet<>();
    private final Map<Location, Integer> thorns = new HashMap<>();
    private final Map<UUID, Long> choiceCooldown = new HashMap<>();
    private final Map<UUID, Double> damageBy = new HashMap<>();

    /** A smite on its way: follows its target for a moment, then locks on and strikes. */
    private static final class Mark {
        final UUID target; Location at; final int lockAt, strikeAt;
        Mark(UUID target, Location at, int lockAt, int strikeAt) { this.target = target; this.at = at; this.lockAt = lockAt; this.strikeAt = strikeAt; }
    }

    GodManager(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("god.enabled", true);
        flyingPose = !"upright".equalsIgnoreCase(c.getString("god.pose", "swim"));
        scale = Math.max(1, Math.min(16, c.getDouble("god.scale", 9)));
        hitShare = c.getDouble("god.body-hit-damage", 0.5);
        flyBack = c.getDouble("god.flying-offset-back", 0.45);
        flyDown = c.getDouble("god.flying-offset-down", 0.15);
        standDown = c.getDouble("god.standing-offset-down", 0.3);
        sitOffset = c.getDouble("god.throne-sit-offset", 0.0);
        hpBase = c.getDouble("god.effective-health-base", 900);
        hpPerFighter = c.getDouble("god.effective-health-per-fighter", 700);
        smiteDamage = c.getDouble("god.smite-damage", 8);
        napSeconds = Math.max(3, c.getInt("god.nap-seconds", 7));
        napWake = c.getDouble("god.nap-wake-damage", 30);
        sinnerDays = c.getDouble("god.sinner-days", 7);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 1L); }

    void stop() {
        if (task != null) task.cancel();
        stopDeathRays();
        endFight();
        for (UUID id : godOf.values()) { Entity g = Bukkit.getEntity(id); if (g != null) g.remove(); }
        godOf.clear();
    }

    private double height() { return 1.8 * scale; }

    // ================================================================ the figure

    private void tickAll() {
        tick++;
        if (!enabled) return;
        if (tick % 40 == 1) scan();
        for (Iterator<Map.Entry<UUID, UUID>> it = godOf.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, UUID> e = it.next();
            Entity d = Bukkit.getEntity(e.getKey()), g = Bukkit.getEntity(e.getValue());
            if (!(d instanceof EnderDragon dragon) || !present(dragon)) {   // the dragon is gone: God fades into light
                if (g != null) {
                    Location at = g.getLocation().add(0, height() * 0.5, 0);
                    for (Player p : g.getWorld().getPlayers()) {
                        p.playSound(p.getLocation(), "minecraft:entity.generic.explode", 1f, 0.5f);
                        p.playSound(p.getLocation(), "minecraft:entity.lightning_bolt.thunder", 1f, 0.6f);
                        p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1f);
                    }
                    stopDeathRays();
                    fx(Particle.END_ROD, g.getLocation().add(0, height() * 0.5, 0), 400, scale * 0.8, 0.35);
                    g.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, g.getLocation().add(0, height() * 0.5, 0), 3, scale * 0.3, scale * 0.3, scale * 0.3, 0, null, true);
                    g.getWorld().playSound(g.getLocation(), "minecraft:block.beacon.deactivate", 5f, 0.6f);
                    g.remove();
                }
                heading.remove(e.getKey()); lastAt.remove(e.getKey());
                it.remove();
                continue;
            }
            if (g == null || !g.isValid()) { it.remove(); continue; }
            follow(dragon, g);
        }
        tickFight();
    }

    /** Give every dragon a God, and clear away any God left without a dragon (after a restart, say). */
    private void scan() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (EnderDragon d : w.getEntitiesByClass(EnderDragon.class)) {
                UUID gid = godOf.get(d.getUniqueId());
                Entity g = gid == null ? null : Bukkit.getEntity(gid);
                if (g == null || !g.isValid()) spawnGod(d);
            }
            for (Entity g : w.getEntities()) {
                if (g.getScoreboardTags().contains(TAG) && !godOf.containsValue(g.getUniqueId())) g.remove();
                if (g.getScoreboardTags().contains(TAG + "_seat") && (seat == null || !seat.equals(g))) g.remove();
                if (g.getScoreboardTags().contains(TAG + "_ray") && rays.isEmpty()) g.remove();
            }
        }
    }

    private void spawnGod(EnderDragon d) {
        Location l = d.getLocation();
        Entity g = summon(l.clone().subtract(0, height() * 0.5, 0), TAG);
        if (g == null) {
            plugin.getLogger().warning("Couldn't create God for the dragon at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                    + ". Check the console just above this line for the summon error.");
            return;
        }
        g.setPersistent(false);
        godOf.put(d.getUniqueId(), g.getUniqueId());
        plugin.getLogger().info("God has appeared in " + l.getWorld().getName() + ".");
    }

    /** Summon a God figure at a spot, with the given tag. Null if the summon didn't work. */
    Entity summon(Location at, String tag) {
        String cmd = String.format(Locale.ROOT,
                "execute in %s run summon minecraft:mannequin %.2f %.2f %.2f {Tags:[\"%s\",\"%s_new\"],profile:{texture:\"soulbound:entity/god\",model:\"wide\"},"
                        + "immovable:1b,hide_description:1b,NoGravity:1b,Silent:1b,attributes:[{id:\"minecraft:scale\",base:%.2f},{id:\"minecraft:max_health\",base:1000}],Health:1000.0f}",
                at.getWorld().getKey().asString(), at.getX(), at.getY(), at.getZ(), tag, tag, scale);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        for (Entity e : at.getWorld().getNearbyEntities(at, 8, height() + 8, 8)) {
            if (!e.getScoreboardTags().contains(tag + "_new")) continue;
            e.removeScoreboardTag(tag + "_new");
            return e;
        }
        return null;
    }

    /** Admin test: a standing God right here, no dragon, to check the skin and size. */
    String test(Player p) {
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(scale * 1.5 + 3));
        at.setYaw(p.getLocation().getYaw() + 180);
        Entity g = summon(at, TAG + "_test");
        if (g == null) return "The summon failed. The server console shows why (look for a red error just before this).";
        g.setRotation(at.getYaw(), 0);
        return "God summoned in front of you. Remove him with /kill @e[tag=" + TAG + "_test]";
    }

    /** Where he sits: the throne the plugin built, or above the middle of the island if there isn't one. */
    private Location seatSpot(World w) {
        String s = store.data.getString("heaven.seat");
        if (s != null) {
            String[] p = s.split(",");
            try { return new Location(w, Double.parseDouble(p[0]), Double.parseDouble(p[1]) + sitOffset, Double.parseDouble(p[2]), 0, 0); }
            catch (Exception ignored) { }
        }
        return new Location(w, 0.5, w.getHighestBlockYAt(0, -17) + 0.5 + sitOffset, -16.5, 0, 0);   // the throne seat
    }

    private boolean seated() { return seat != null && seat.isValid(); }

    /** Keep God where he belongs: on his throne, swimming through the sky with the dragon, or standing when it lands. */
    private void follow(EnderDragon d, Entity g) {
        Location dl = d.getLocation();
        UUID id = d.getUniqueId();
        if (seated()) {
            if (!seat.getPassengers().contains(g)) seat.addPassenger(g);
            g.setRotation(0, napping ? 55 : 10);
            if (g instanceof LivingEntity le) le.setBodyYaw(0);
            // the dragon waits, invisible, just above him
            Location hold = seat.getLocation().add(0, height() * 0.6, 0);
            if (dl.distanceSquared(hold) > 4) d.teleport(hold);
            if (d.getPhase() != EnderDragon.Phase.HOVER && d.getPhase() != EnderDragon.Phase.DYING) d.setPhase(EnderDragon.Phase.HOVER);
            halo(seat.getLocation().add(0, height() * 0.78, 0));
            return;
        }
        Location prev = lastAt.put(id, dl.clone());
        Vector dir = heading.getOrDefault(id, new Vector(0, 0, 1));
        if (prev != null && prev.getWorld() == dl.getWorld()) {
            Vector v = dl.toVector().subtract(prev.toVector());
            if (v.lengthSquared() > 0.0025) dir = dir.multiply(0.85).add(v.normalize().multiply(0.15)).normalize();
        }
        heading.put(id, dir);
        boolean standing = napping || !flyingPose || switch (d.getPhase()) {
            case LAND_ON_PORTAL, BREATH_ATTACK, SEARCH_FOR_BREATH_ATTACK_TARGET, ROAR_BEFORE_ATTACK, HOVER, DYING -> true;
            default -> false;
        };
        double h = height();
        Vector flat = new Vector(dir.getX(), 0, dir.getZ());
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0, 0, 1);
        flat.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        Location to;
        float pitch;
        if (standing) {
            to = dl.clone().subtract(0, h * standDown, 0);
            pitch = napping ? 55 : 0;
        } else {
            to = dl.clone().subtract(dir.clone().multiply(h * flyBack)).subtract(0, h * flyDown, 0);
            pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.getY()))));
        }
        to.setYaw(yaw);
        to.setPitch(pitch);
        g.teleport(to);
        Pose want = standing ? Pose.STANDING : Pose.FALL_FLYING;
        if (g.getPose() != want) g.setPose(want, true);
        if (g instanceof LivingEntity le) le.setBodyYaw(yaw);
        halo(standing ? to.clone().add(0, h * 1.02, 0) : to.clone().add(dir.clone().multiply(h * 0.98)).add(0, h * 0.12, 0));
    }

    private void halo(Location head) {
        if (tick % 3 != 0) return;
        double r = 0.32 * scale;
        for (int i = 0; i < 14; i++) {
            double a = i * Math.PI * 2 / 14 + tick * 0.05;
            head.getWorld().spawnParticle(Particle.DUST, head.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, HALO, true);
        }
    }

    private Entity godFigure() {
        UUID g = fightDragon == null ? null : godOf.get(fightDragon);
        return g == null ? null : Bukkit.getEntity(g);
    }

    private EnderDragon dragon() {
        return fightDragon != null && Bukkit.getEntity(fightDragon) instanceof EnderDragon d && present(d) ? d : null;
    }

    /** Dying, the dragon flies home to the portal at 1 health, then plays out its last ten seconds at 0 health,
     *  when Bukkit already calls it dead. It's still there until it's removed at the very end. */
    private static boolean dying(EnderDragon d) { return d.getPhase() == EnderDragon.Phase.DYING || d.getHealth() <= 0 || d.isDead(); }
    private static boolean present(EnderDragon d) { return d.isValid() || dying(d);
    }

    // ================================================================ hits

    private static Player attacker(Entity src) {
        if (src instanceof Player pl) return pl;
        if (src instanceof Projectile pr && pr.getShooter() instanceof Player sh) return sh;
        return null;
    }

    /** Hits on God's body are passed on to the dragon (at a share of their damage); nothing else touches him. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onGodHurt(EntityDamageEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(TAG)) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent ed)) return;
        Player p = attacker(ed.getDamager());
        if (p == null) return;
        if (ed.getDamager() instanceof Projectile) ed.getDamager().remove();
        EnderDragon d = dragonOf(e.getEntity().getUniqueId());
        if (d == null || !d.isValid() || d.getPhase() == EnderDragon.Phase.DYING || d.getHealth() <= 0) return;
        double raw = e.getDamage() * hitShare * (napping ? 2 : 1);
        onHurtBy(p, raw);
        double amount = raw * toughness;
        if (d.getHealth() - amount <= 0) d.damage(d.getHealth() * 4 + 10, p);   // the killing blow goes through the dragon, so the kill counts
        else {
            d.setHealth(d.getHealth() - amount);
            Location at = e.getEntity().getLocation().add(0, height() * 0.5, 0);
            at.getWorld().playSound(at, "minecraft:entity.ender_dragon.hurt", 4f, 1f);
            fx(Particle.DAMAGE_INDICATOR, p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(2)), 4, 0.2, 0.1);
        }
    }

    /** Hits on the dragon's own (invisible) hitbox: double while he naps. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderDragon d) || !d.getUniqueId().equals(fightDragon)) return;
        Player p = attacker(e.getDamager());
        if (p == null) return;
        if (napping) e.setDamage(e.getDamage() * 2);
        onHurtBy(p, e.getDamage());
        e.setDamage(e.getDamage() * toughness);   // scaled to the crowd
    }

    private void onHurtBy(Player p, double amount) {
        damageBy.merge(p.getUniqueId(), amount, Double::sum);
        if (peaceful) { strikeFirst(p); return; }
        if (napping) {
            napDamage += amount;
            if (napDamage >= napWake) wake(true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHitLine > 9000 && rnd.nextInt(5) == 0) {
            lastHitLine = now;
            say(pick("Ow.", "I made those arms, you know.", "Is that how you treat your elders?", "Mind the beard!",
                    "I'll be telling your mother.", "Ooh, my hip.", "Was that necessary?"));
        }
    }

    private EnderDragon dragonOf(UUID god) {
        for (Map.Entry<UUID, UUID> e : godOf.entrySet())
            if (e.getValue().equals(god) && Bukkit.getEntity(e.getKey()) instanceof EnderDragon d) return d;
        return null;
    }

    // ================================================================ the fight

    private List<Player> fighters(World w) {
        List<Player> out = new ArrayList<>();
        for (Player p : w.getPlayers())
            if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead()
                    && Math.hypot(p.getLocation().getX(), p.getLocation().getZ()) < 160) out.add(p);
        return out;
    }

    private double healthShare(EnderDragon d) {
        var a = d.getAttribute(Attribute.MAX_HEALTH);
        return d.getHealth() / (a != null ? a.getValue() : 200);
    }

    private void tickFight() {
        EnderDragon d = null; Entity g = null;
        for (Map.Entry<UUID, UUID> e : godOf.entrySet()) {
            if (Bukkit.getEntity(e.getKey()) instanceof EnderDragon dr && present(dr)) { d = dr; g = Bukkit.getEntity(e.getValue()); break; }
        }
        if (d == null || g == null) { if (fightDragon != null) endFight(); return; }
        if (!d.getUniqueId().equals(fightDragon)) newFight(d);
        World w = d.getWorld();

        if (dying(d)) {
            if (!deathSaid) {
                deathSaid = true;
                Title fall = Title.title(Component.text("IT IS FINISHED", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("God has been defeated", NamedTextColor.WHITE), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(1200)));
                for (Player p : w.getPlayers()) {
                    p.showTitle(fall);
                    p.playSound(p.getLocation(), "minecraft:entity.lightning_bolt.thunder", 1f, 0.5f);
                    p.playSound(p.getLocation(), "soulbound:god.voice", 1f, 0.9f);
                }
                if (napping) wake(false);
                if (seated()) rise(d, false);
                store.set("god.defeated-once", true); store.save();
                final World ww = w;
                for (int k = 0; k <= 200; k += 5) {   // night breaks into a new day over ten seconds
                    long t = (21000 + k * 45L) % 24000;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> setEndTime(ww, t), 60 + k);
                }
                awardHalo();
                startDeathRays();
                String[] last = LAST_WORDS[rnd.nextInt(LAST_WORDS.length)];   // he only LET you win, of course
                say(last[0]);
                Bukkit.getScheduler().runTaskLater(plugin, () -> say(last[1]), 60L);
                clearSinners();
                clearAngels();
            }
            return;
        }
        tickThorns();
        tickMarks(w, d);
        tickFrozen();
        List<Player> f = fighters(w);

        if (peaceful) { tickAudience(f, d, g); return; }

        if (f.isEmpty()) {
            if (hadFighters && ++emptyTicks == 20 * 60) {   // everyone left: back to his throne
                say("Bedtime, everyone.");
                hadFighters = false;
                returnToThrone(d);
            }
            return;
        }
        emptyTicks = 0;
        hadFighters = true;

        toughness = Math.min(1, 200.0 / (hpBase + hpPerFighter * f.size()));   // the dragon has 200 health: he lasts as if he had more
        double r = healthShare(d);
        int now = Math.max(1, Math.min(7, 1 + (int) Math.floor((1 - r) * 7)));
        if (now > day) newDay(now, d, g, f);
        if (!lowSaid && r <= 0.1) { lowSaid = true; say("Back in my day the meek inherited the earth. Now they just hit me with swords."); }
        if (saltTicks >= 0) tickSalt(f, g);
        if (day == 1) keepOffFurniture(f);
        if (day == 7) { tickRest(d, g, f); return; }
        if (napping) { tickNap(d, g); return; }
        if (--nextAbility <= 0) { ability(f, d, g); nextAbility = gap(); }
    }

    /** His damage grows through the week: +12% a day. */
    private double fury() { return 1 + 0.15 * Math.max(0, day - 1); }

    private int gap() {
        return switch (day) { case 1 -> 140; case 2, 3 -> 120; case 4, 5 -> 105; case 6 -> 75; default -> 120; };
    }

    private void newFight(EnderDragon d) {
        endFight();
        fightDragon = d.getUniqueId();
        peaceful = true;
        day = 0;
        sitDown(d);
    }

    // ---------------------------------------------------------------- Day 0: the audience

    private void sitDown(EnderDragon d) {
        if (peaceful) setEndTime(d.getWorld(), 18000);   // he holds court at night
        Entity g = godOf.get(d.getUniqueId()) == null ? null : Bukkit.getEntity(godOf.get(d.getUniqueId()));
        if (g == null || seated()) return;
        Location s = seatSpot(d.getWorld());
        seat = s.getWorld().spawn(s, ArmorStand.class, a -> {
            a.setMarker(true); a.setInvisible(true); a.setGravity(false); a.setInvulnerable(true); a.setPersistent(false);
            a.addScoreboardTag(TAG + "_seat");
        });
        if (g.getPose() != Pose.STANDING) g.setPose(Pose.STANDING, false);
        seat.addPassenger(g);
    }

    private void tickAudience(List<Player> f, EnderDragon d, Entity g) {
        if (!seated()) sitDown(d);
        Location throne = seated() ? seat.getLocation() : g.getLocation();
        boolean anyNear = false;
        for (Player p : f) {
            if (p.getLocation().distanceSquared(throne) > 40 * 40) continue;
            anyNear = true;
            if (greeted.add(p.getUniqueId())) greet(p);
        }
        if (anyNear) {
            if (++nearTicks == 20 * 120 && !idleSaid) { idleSaid = true; say("Well? I haven't got all eternity. Actually, I have. But still."); }
        } else nearTicks = 0;
    }

    private void greet(Player p) {
        boolean again = store.data.getBoolean("god.defeated-once", false);
        String hello = again ? "Oh, you AGAIN." : pick("Ah. Visitors. Wipe your feet.", "Oh. It's you lot. What do you want?",
                "Nobody visits. Then they all turn up at once, with swords.", "Come closer. My eyes aren't what they were. I made them, you'd think I'd have done better.");
        tell(p, hello);
        // what he notices about you
        ItemStack chest = p.getInventory().getChestplate();
        String remark = null;
        if (isSinner(p)) remark = "I can smell the smoke on you.";
        else if (chest != null && chest.getType() == Material.ELYTRA) remark = "Wings? You stole those off a ship. I saw.";
        else if (wearsNetherite(p)) remark = "Ugh. Netherite. Hell's jewellery.";
        String r = remark;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (r != null) tell(p, r);
            tell(p, "Why are you here?");
            p.sendMessage(Component.text("  ")
                    .append(choice("I've come to challenge you", "challenge")).append(Component.text("  "))
                    .append(choice("I'm lost", "lost")).append(Component.text("  "))
                    .append(choice("Forgive me", "forgive")).append(Component.text("  "))
                    .append(choice("Just looking", "look")));
        }, 50L);
    }

    private static boolean wearsNetherite(Player p) {
        for (ItemStack i : p.getInventory().getArmorContents()) if (i != null && i.getType().name().startsWith("NETHERITE")) return true;
        return false;
    }

    private Component choice(String label, String id) {
        return Component.text("[" + label + "]", NamedTextColor.GOLD)
                .clickEvent(ClickEvent.runCommand("/god " + id))
                .hoverEvent(HoverEvent.showText(Component.text("Say it to God", NamedTextColor.GRAY)));
    }

    /** A player's answer to "Why are you here?" (from the clickable chat). */
    void choose(Player p, String what) {
        if (fightDragon == null || p.getWorld().getEnvironment() != World.Environment.THE_END) { p.sendMessage(Component.text("There's nobody here to answer.", NamedTextColor.GRAY)); return; }
        long now = System.currentTimeMillis();
        if (now - choiceCooldown.getOrDefault(p.getUniqueId(), 0L) < 3000) return;
        choiceCooldown.put(p.getUniqueId(), now);
        switch (what.toLowerCase(Locale.ROOT)) {
            case "challenge" -> {
                if (!peaceful) { tell(p, "We're already doing that."); return; }
                say("Challenge ME? I invented challenges.");
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (peaceful) startFight(p, false); }, 40L);
            }
            case "lost" -> {
                tell(p, "Lost. In the End. Of course you are. Off you pop.");
                Location home = p.getRespawnLocation();
                if (home == null) home = Bukkit.getWorlds().get(0).getSpawnLocation();
                Location h = home;
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.teleport(h); }, 30L);
            }
            case "forgive" -> {
                if (isSinner(p)) {
                    tell(p, "Forgive you? You fed my dinner to that furnace fellow. No.");
                    markSinner(p);
                } else {
                    tell(p, "Hm. Nothing to forgive. Suspicious. Here, have this.");
                    p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 60, 0));
                }
            }
            case "look" -> tell(p, "This isn't a museum.");
            default -> { }
        }
    }

    /** Someone hit him while he was being polite. */
    private void strikeFirst(Player p) {
        if (!peaceful) return;
        say("RUDE. I was TALKING.");
        startFight(p, true);
    }

    /** The End's clock (frozen, so it stays wherever God puts it). 18000 midnight, 6000 noon. */
    /**
     * The End keeps its own clock (minecraft:the_end) since 26.1; World#setTime moves the main day clock, not that one,
     * so set the End's clock by command (quietly) as well.
     */
    private void setEndTime(World w, long t) {
        if (w == null) return;
        try { w.setTime(t); } catch (Throwable ignored) { }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time of minecraft:the_end set " + Math.floorMod(t, 24000L));
    }

    /** Dawn in three seconds: the clock runs from midnight through sunrise to noon. */
    private void dawn(World w) {
        if (w == null) return;
        for (int k = 0; k <= 60; k += 3) {
            long t = (18000 + k * 200L) % 24000;
            Bukkit.getScheduler().runTaskLater(plugin, () -> setEndTime(w, t), k);
        }
        for (Player p : w.getPlayers()) p.playSound(p.getLocation(), "minecraft:block.beacon.activate", 1f, 0.5f);
    }

    private void startFight(Player starter, boolean rude) {
        peaceful = false;
        EnderDragon dd = dragon();
        if (dd != null) dawn(dd.getWorld());
        EnderDragon d = dragon();
        Entity g = godFigure();
        List<Player> f = d != null ? fighters(d.getWorld()) : List.of();
        // a flash of golden light fills the sky
        if (g != null) {
            fx(Particle.END_ROD, g.getLocation().add(0, height() * 0.6, 0), 300, scale, 0.4);
            g.getWorld().playSound(g.getLocation(), "minecraft:block.beacon.power_select", 6f, 0.5f);
        }
        newDay(1, d, g, f);
        if (rude && starter != null && d != null) smite(List.of(starter), 1, d);
        nextAbility = 100;
    }

    private void returnToThrone(EnderDragon d) {
        if (napping) wake(false);
        setEndTime(d.getWorld(), 18000);
        peaceful = true;
        greeted.clear();
        idleSaid = false;
        clearAngels();
        sitDown(d);
    }

    // ---------------------------------------------------------------- the days

    private void newDay(int n, EnderDragon d, Entity g, List<Player> f) {
        day = n;
        Title t = Title.title(Component.text(DAY_TITLES[n], NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(DAY_LINES[n], NamedTextColor.WHITE), Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(800)));
        if (d != null) for (Player p : d.getWorld().getPlayers()) {
            p.showTitle(t);
            p.playSound(p.getLocation(), "minecraft:block.bell.resonate", 1f, 0.6f + 0.05f * n);
        }
        say(DAY_LINES[n]);
        if (d == null) return;
        if (n >= 2 && seated()) rise(d, true);
        else if (n >= 3) d.getWorld().playSound(d.getLocation(), "soulbound:god.voice", 6f, 1f);   // each new day, his voice rolls across the sky
        if (n == 5 && !judged) Bukkit.getScheduler().runTaskLater(plugin, () -> judgement(fighters(d.getWorld())), 80L);
        if (n == 7) nextNap = 60;
        nextAbility = 80;
    }

    /** Day 2: he gets up off his throne and takes to the sky. */
    private void rise(EnderDragon d, boolean loud) {
        if (seat != null) { seat.eject(); seat.remove(); seat = null; }
        if (d.getPhase() != EnderDragon.Phase.DYING) d.setPhase(EnderDragon.Phase.CIRCLING);
        if (loud) {
            Location at = d.getLocation();
            at.getWorld().playSound(at, "soulbound:god.voice", 6f, 1f);
            fx(Particle.CLOUD, at, 120, scale, 0.15);
        }
    }

    private void ability(List<Player> f, EnderDragon d, Entity g) {
        List<Runnable> pool = new ArrayList<>();
        int smites = switch (day) { case 1 -> 1; case 6 -> 3 + f.size() / 4; default -> 2; };
        pool.add(() -> smite(f, smites, d));
        switch (day) {
            case 1 -> { pool.add(() -> orbs(f, d, g)); pool.add(() -> orbs(f, d, g)); }
            case 2 -> { pool.add(() -> rapture(f)); pool.add(() -> rapture(f)); }
            case 3 -> { pool.add(() -> rapture(f)); pool.add(() -> host(d)); pool.add(() -> host(d)); }
            case 4 -> { pool.add(() -> rapture(f)); pool.add(() -> stars(f, d)); pool.add(() -> stars(f, d)); if (saltTicks < 0) pool.add(() -> startSalt(f)); }
            case 5 -> { pool.add(() -> angels(f, g)); pool.add(() -> angels(f, g)); pool.add(() -> stars(f, d)); if (saltTicks < 0) pool.add(() -> startSalt(f)); }
            case 6 -> { pool.add(() -> throwThrone(f, g)); pool.add(() -> lawn(f, g)); pool.add(() -> angels(f, g)); }
            default -> { }
        }
        pool.get(rnd.nextInt(pool.size())).run();
    }

    // ---- Smite: a pillar of gold light finds you, then lightning comes down where it stands

    private void smite(List<Player> f, int count, EnderDragon d) {
        List<Player> order = new ArrayList<>(f);
        Collections.shuffle(order, rnd);
        order.sort((a, b) -> Boolean.compare(sinners.contains(b.getUniqueId()), sinners.contains(a.getUniqueId())));   // sinners first
        for (int i = 0; i < Math.min(count, order.size()); i++) {
            Player p = order.get(i);
            marks.add(new Mark(p.getUniqueId(), p.getLocation(), tick + 22, tick + 34));
            p.sendActionBar(Component.text("A light falls on you. MOVE.", NamedTextColor.GOLD, TextDecoration.BOLD));
            p.playSound(p.getLocation(), "minecraft:block.beacon.activate", 1f, 1.6f);
        }
        say(pick("Smite.", "THOU SHALT NOT... stand there.", "Hold still.", "This hurts me more than it hurts you.", "Smite. Smite. Smite."));
    }

    private void tickMarks(World w, EnderDragon d) {
        for (Iterator<Mark> it = marks.iterator(); it.hasNext(); ) {
            Mark m = it.next();
            Player p = Bukkit.getPlayer(m.target);
            if (tick < m.lockAt && p != null && p.getWorld() == w) m.at = p.getLocation();
            if (tick % 2 == 0) for (double y = 0; y < 30; y += 0.8)
                w.spawnParticle(Particle.DUST, m.at.clone().add(0, y, 0), 1, 0.15, 0, 0.15, 0, BEAM, true);
            if (tick < m.strikeAt) continue;
            it.remove();
            w.strikeLightningEffect(m.at);
            fx(Particle.END_ROD, m.at.clone().add(0, 0.5, 0), 40, 0.6, 0.15);
            for (Player q : w.getPlayers()) {
                if (q.getLocation().distanceSquared(m.at) > 2.8 * 2.8 || q.isDead()) continue;
                q.damage(fury() * smiteDamage * (sinners.contains(q.getUniqueId()) ? 2 : 1), d);
                q.setFireTicks(60);
            }
        }
    }

    // ---- Day 1: golden orbs from his hand, and keep off the throne

    private void orbs(List<Player> f, EnderDragon d, Entity g) {
        Location hand = (seated() ? seat.getLocation() : g.getLocation()).add(0, height() * 0.45, 2);
        List<Player> order = new ArrayList<>(f);
        Collections.shuffle(order, rnd);
        for (int i = 0; i < Math.min(5, order.size() * 2); i++) {
            Player p = order.get(i % order.size());
            Vector dir = p.getEyeLocation().toVector().subtract(hand.toVector());
            if (dir.lengthSquared() < 1) continue;
            Vector v = dir.normalize();
            hand.getWorld().spawn(hand, DragonFireball.class, fb -> {
                fb.setShooter(d);
                fb.setDirection(v);
                fb.setVelocity(v.clone().multiply(1.1));
            });
        }
        say(pick("Catch.", "Here. A little light for you.", "Butterfingers."));
        hand.getWorld().playSound(hand, "minecraft:entity.ender_dragon.shoot", 4f, 0.8f);
    }

    private void keepOffFurniture(List<Player> f) {
        if (!seated() || tick % 10 != 0) return;
        Location s = seat.getLocation();
        for (Player p : f) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - s.getX()) > 6 || l.getZ() < s.getZ() - 5 || l.getZ() > s.getZ() + 4 || l.getY() < s.getY() - 4) continue;
            p.setVelocity(new Vector((l.getX() - s.getX()) * 0.15, 0.8, 1.6));
            if (System.currentTimeMillis() - lastLine > 5000) say(pick("Off the furniture!", "That's MY chair.", "Feet off the throne."));
        }
    }

    // ---- Day 2: Rapture, players lifted into the air

    private void rapture(List<Player> f) {
        List<Player> order = new ArrayList<>(f);
        Collections.shuffle(order, rnd);
        int n = Math.min(order.size(), 1 + rnd.nextInt(3));
        for (int i = 0; i < n; i++) {
            Player p = order.get(i);
            p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 70, 1, false, false, true));
            fx(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.5, 0.05);
            p.playSound(p.getLocation(), "minecraft:block.beacon.power_select", 1f, 1.8f);
            p.sendActionBar(Component.text("You are lifted up. Hope you can land.", NamedTextColor.GOLD));
        }
        say(pick("Rise, my child.", "Up you go.", "Ascend! Mind the landing."));
    }

    // ---- Day 3: the heavenly host - two Archangels come down to help him (no more than two at once)

    private void host(EnderDragon d) {
        Location at = d.getLocation();
        int alive = plugin.archangels().countNear(at, 220);
        if (alive >= 2) { say(pick("Archangels! Smite them, would you?", "My host is busy. Busy smiting you.")); return; }
        for (int i = alive; i < 2; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location spot = at.clone().add(Math.cos(a) * 14, 4 + rnd.nextInt(6), Math.sin(a) * 14);
            plugin.archangels().summon(spot);
            fx(Particle.END_ROD, spot, 80, 2.5, 0.12);
            spot.getWorld().strikeLightningEffect(spot);
        }
        at.getWorld().playSound(at, "minecraft:block.beacon.activate", 6f, 0.6f);
        say(pick("Archangels! Attend me.", "I brought help. Don't tell anyone.", "My host. Be polite, they bite."));
    }

    // ---- (old) thorns burst up around players (they wither away again)

    private void thorns(List<Player> f) {
        BlockData bush = Material.SWEET_BERRY_BUSH.createBlockData();
        if (bush instanceof Ageable a) a.setAge(3);
        for (Player p : f) {
            if (rnd.nextInt(2) == 0) continue;
            Location c = p.getLocation();
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz > 9 || rnd.nextInt(3) == 0) continue;
                Block b = c.getWorld().getBlockAt(c.getBlockX() + dx, c.getBlockY(), c.getBlockZ() + dz);
                if (!b.getType().isAir() || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
                b.setBlockData(bush, false);
                thorns.put(b.getLocation(), tick + 200);
            }
            p.playSound(c, "minecraft:block.sweet_berry_bush.place", 1.5f, 0.6f);
        }
        say(pick("Let the earth bring forth... thorns.", "Mind your ankles.", "Gardening."));
    }

    private void tickThorns() {
        for (Iterator<Map.Entry<Location, Integer>> it = thorns.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Location, Integer> e = it.next();
            if (tick < e.getValue()) continue;
            Block b = e.getKey().getBlock();
            if (b.getType() == Material.SWEET_BERRY_BUSH) { b.setType(Material.AIR, false); fx(Particle.POOF, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.2, 0.01); }
            it.remove();
        }
    }

    // ---- Day 4: falling stars

    private void stars(List<Player> f, EnderDragon d) {
        say(pick("Make a wish.", "Lights in the heavens! Duck.", "Shooting stars. For you."));
        for (int k = 0; k < 8; k++) {
            int delay = k * 8;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (f.isEmpty() || !d.isValid()) return;
                Player p = f.get(rnd.nextInt(f.size()));
                if (!p.isValid()) return;
                Location from = p.getLocation().add(rnd.nextGaussian() * 6, 30, rnd.nextGaussian() * 6);
                Vector v = new Vector(rnd.nextGaussian() * 0.05, -1, rnd.nextGaussian() * 0.05).normalize();
                from.getWorld().spawn(from, DragonFireball.class, fb -> { fb.setShooter(d); fb.setDirection(v); fb.setVelocity(v.clone().multiply(1.3)); });
                fx(Particle.END_ROD, from, 10, 0.4, 0.05);
            }, delay);
        }
    }

    // ---- Pillar of Salt: "Do not look upon me." Anyone looking at him is frozen solid

    private void startSalt(List<Player> f) {
        saltTicks = 0;
        say("Avert thine eyes. It's rude to stare.");
        Title t = Title.title(Component.text("AVERT THINE EYES", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Do not look upon God", NamedTextColor.GOLD), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(300)));
        for (Player p : f) { p.showTitle(t); p.playSound(p.getLocation(), "minecraft:block.bell.resonate", 1f, 0.8f); }
    }

    private void tickSalt(List<Player> f, Entity g) {
        saltTicks++;
        if (saltTicks < 40) return;
        if (saltTicks > 40 + 80) { saltTicks = -1; return; }
        if (saltTicks % 2 != 0) return;
        Location face = g.getLocation().add(0, height() * 0.85, 0);
        for (Player p : f) {
            if (frozen.containsKey(p.getUniqueId()) || saltImmune.getOrDefault(p.getUniqueId(), -1) > tick) continue;
            Vector to = face.toVector().subtract(p.getEyeLocation().toVector());
            if (to.lengthSquared() < 1) continue;
            if (p.getEyeLocation().getDirection().dot(to.normalize()) < Math.cos(Math.toRadians(28))) continue;
            frozen.put(p.getUniqueId(), p.getLocation());
            frozenUntil.put(p.getUniqueId(), tick + 60);
            saltImmune.put(p.getUniqueId(), tick + 120);
            p.damage(2);
            p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 4, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 4, false, false, true));
            p.playSound(p.getLocation(), "minecraft:block.sand.place", 1.5f, 0.6f);
            p.showTitle(Title.title(Component.empty(), Component.text("You looked. You are salt.", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))));
            if (System.currentTimeMillis() - lastLine > 4000) say(pick("I said don't look.", "Rude.", "What did I JUST say?"));
        }
    }

    private void tickFrozen() {
        for (Iterator<Map.Entry<UUID, Location>> it = frozen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Location> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || p.isDead() || tick >= frozenUntil.getOrDefault(e.getKey(), 0)) { it.remove(); frozenUntil.remove(e.getKey()); continue; }
            Location hold = e.getValue();
            if (p.getWorld() == hold.getWorld() && p.getLocation().distanceSquared(hold) > 0.04) {
                Location back = hold.clone(); back.setYaw(p.getLocation().getYaw()); back.setPitch(p.getLocation().getPitch());
                p.teleport(back);
            }
            p.setVelocity(new Vector(0, Math.min(0, p.getVelocity().getY()), 0));
            if (tick % 3 == 0) p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0, SALT, true);
        }
    }

    // ---- Day 5: angels, and Judgement

    private void angels(List<Player> f, Entity g) {
        int n = Math.min(14, 3 + (int) Math.round(f.size() * 1.5));
        Location from = g.getLocation().add(0, height() * 0.6, 0);
        for (int i = 0; i < n; i++) {
            Location at = from.clone().add(rnd.nextGaussian() * 3, rnd.nextGaussian() * 2, rnd.nextGaussian() * 3);
            Vex v = at.getWorld().spawn(at, Vex.class, vx -> {
                vx.customName(Component.text("Angel", NamedTextColor.GOLD));
                vx.setLimitedLifetime(true);
                vx.setLimitedLifetimeTicks(20 * 30);
                vx.setPersistent(false);
                vx.addScoreboardTag(ANGEL);
            });
            angels.add(v.getUniqueId());
            List<Player> pick = new ArrayList<>(f);
            pick.sort((a, b) -> Boolean.compare(sinners.contains(b.getUniqueId()), sinners.contains(a.getUniqueId())));
            if (!pick.isEmpty()) v.setTarget(pick.get(rnd.nextInt(Math.min(pick.size(), Math.max(1, sinners.size())))));
            fx(Particle.END_ROD, at, 15, 0.4, 0.05);
        }
        g.getWorld().playSound(g.getLocation(), "minecraft:block.amethyst_block.resonate", 6f, 1.4f);
        say(pick("Angels! Sic 'em.", "Go on, my little ones.", "Heavenly host, assemble. Bite them."));
    }

    /** Angels only go for players. */
    @EventHandler(ignoreCancelled = true)
    public void onAngelTarget(EntityTargetEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ANGEL) && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true);
    }

    private void clearAngels() {
        for (UUID id : angels) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        angels.clear();
    }

    private boolean isSinner(Player p) {
        long since = System.currentTimeMillis() - (long) (sinnerDays * 86_400_000L);
        return store.data.getLong("sacrifice.last-offered-by." + p.getUniqueId(), 0) >= since;
    }

    private void markSinner(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam("soulbound_sinners");
        if (team == null) {
            team = sb.registerNewTeam("soulbound_sinners");
            team.color(NamedTextColor.RED);
            team.suffix(Component.text(" SINNER", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        }
        sinners.add(p.getUniqueId());
        team.addEntry(p.getName());
        p.setGlowing(true);
    }

    private void judgement(List<Player> f) {
        judged = true;
        say("Now. Who's been feeding that Moloch fellow?");
        List<String> named = new ArrayList<>();
        for (Player p : f) if (isSinner(p)) { markSinner(p); named.add(p.getName()); }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (named.isEmpty()) say("Nobody? Hm. I'll find out.");
            else {
                for (String n : named) say("I can SEE you, " + n + ".");
                say("Sinners. You'll be smitten first. And harder.");
            }
        }, 50L);
    }

    private void clearSinners() {
        for (UUID id : sinners) { Player p = Bukkit.getPlayer(id); if (p != null) p.setGlowing(false); }
        sinners.clear();
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("soulbound_sinners");
        if (t != null) t.unregister();
    }

    // ---- Day 6: "Fine. FINE." He hurls his throne, and tells everyone to get off his lawn

    private void throwThrone(List<Player> f, Entity g) {
        Location from = seatSpot(g.getWorld()).add(0, 6, -1);
        say(pick("You want the throne? HAVE IT.", "Catch, you ungrateful lot!", "I never liked that chair."));
        from.getWorld().playSound(from, "minecraft:block.stone.break", 6f, 0.5f);
        Material[] chunks = {Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ, Material.GOLD_BLOCK, Material.CHISELED_QUARTZ_BLOCK};
        for (Player p : f) {
            for (int k = 0; k < 2; k++) {
                Location to = p.getLocation().add(rnd.nextGaussian() * 2, 0, rnd.nextGaussian() * 2);
                Vector d = to.toVector().subtract(from.toVector());
                double t = 30;
                Vector v = new Vector(d.getX() / t, (d.getY() + 0.5 * 0.04 * t * t) / t, d.getZ() / t);
                FallingBlock fb = from.getWorld().spawnFallingBlock(from.clone().add(rnd.nextGaussian(), 0, rnd.nextGaussian()), chunks[rnd.nextInt(chunks.length)].createBlockData());
                fb.setDropItem(false);
                fb.setHurtEntities(false);
                fb.setPersistent(false);
                fb.addScoreboardTag(RELIC);
                fb.setVelocity(v);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRelicLands(EntityChangeBlockEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(RELIC)) return;
        e.setCancelled(true);
        Location l = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (e.getEntity() instanceof FallingBlock fb) l.getWorld().spawnParticle(Particle.BLOCK, l, 40, 0.6, 0.4, 0.6, 0.1, fb.getBlockData(), true);
        l.getWorld().spawnParticle(Particle.EXPLOSION, l, 1, 0, 0, 0, 0, null, true);
        l.getWorld().playSound(l, "minecraft:block.anvil.land", 2f, 0.7f);
        EnderDragon d = dragon();
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 3 * 3) { if (d != null) p.damage(10 * fury(), d); else p.damage(10 * fury()); }
        e.getEntity().remove();
    }

    private void lawn(List<Player> f, Entity g) {
        say("GET OFF MY LAWN!");
        Location c = new Location(g.getWorld(), 0.5, g.getLocation().getY(), 0.5);
        for (int ring = 1; ring <= 4; ring++) {
            int r = ring;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                double rad = r * 8;
                for (int i = 0; i < 36; i++) {
                    double a = i * Math.PI * 2 / 36;
                    Location at = new Location(c.getWorld(), Math.cos(a) * rad, 0, Math.sin(a) * rad);
                    at.setY(c.getWorld().getHighestBlockYAt(at) + 1.2);
                    c.getWorld().spawnParticle(Particle.CLOUD, at, 3, 0.3, 0.2, 0.3, 0.05, null, true);
                }
            }, r * 2L);
        }
        for (Player p : f) {
            Vector out = p.getLocation().toVector().subtract(c.toVector()); out.setY(0);
            if (out.lengthSquared() < 1e-3) out = new Vector(1, 0, 0);
            p.setVelocity(out.normalize().multiply(2.2).setY(0.7));
            if (dragon() != null) p.damage(6 * fury(), dragon());
            p.playSound(p.getLocation(), "minecraft:entity.generic.explode", 1f, 0.6f);
        }
    }

    // ---- Day 7: he rested. Dozes off again and again; hit him too hard and he wakes furious

    private void tickRest(EnderDragon d, Entity g, List<Player> f) {
        if (napping) { tickNap(d, g); return; }
        if (--nextNap <= 0 && isFlying(d)) { startNap(d, g); return; }
        if (--nextAbility <= 0) { smite(f, 2, d); nextAbility = 150; }
    }

    private static boolean isFlying(EnderDragon d) {
        return switch (d.getPhase()) {
            case CIRCLING, STRAFING, FLY_TO_PORTAL, CHARGE_PLAYER, LEAVE_PORTAL -> true;
            default -> false;
        };
    }

    private void startNap(EnderDragon d, Entity g) {
        napping = true;
        napTicks = 0;
        napDamage = 0;
        d.setPhase(EnderDragon.Phase.HOVER);
        say(pick("I'm just... resting my eyes...", "Five minutes...", "Is it Sunday? It feels like Sunday..."));
        Location head = g.getLocation().add(0, height() * 1.15, 0);
        snore = head.getWorld().spawn(head, TextDisplay.class, td -> {
            td.text(Component.text("Z z z", NamedTextColor.WHITE, TextDecoration.BOLD));
            td.setBillboard(Display.Billboard.CENTER);
            td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(8, 8, 8), new AxisAngle4f()));
            td.setPersistent(false);
        });
        for (Player p : fighters(d.getWorld())) p.sendActionBar(Component.text("God has dozed off. Double damage, but don't wake him...", NamedTextColor.GOLD));
    }

    private void tickNap(EnderDragon d, Entity g) {
        napTicks++;
        if (d.getPhase() != EnderDragon.Phase.HOVER) d.setPhase(EnderDragon.Phase.HOVER);
        if (snore != null && snore.isValid()) snore.teleport(g.getLocation().add(0, height() * 1.15 + Math.sin(napTicks * 0.1) * 0.6, 0));
        if (napTicks % 30 == 0) g.getWorld().playSound(g.getLocation(), "minecraft:entity.fox.sleep", 6f, 0.5f);
        if (napTicks >= napSeconds * 20) wake(false);
    }

    private void wake(boolean furious) {
        if (!napping) return;
        napping = false;
        if (snore != null) { snore.remove(); snore = null; }
        EnderDragon d = dragon();
        if (d != null && d.getPhase() != EnderDragon.Phase.DYING) d.setPhase(EnderDragon.Phase.CIRCLING);
        if (furious) {
            say("I WASN'T ASLEEP!");
            if (d != null) { List<Player> f = fighters(d.getWorld()); smite(f, f.size(), d); }
        } else say(pick("Hm? Where was I? Oh yes. Smiting.", "I'm awake. I'm awake.", "Who moved?"));
        nextNap = 20 * (14 + rnd.nextInt(8));
        nextAbility = 60;
    }

    /** Whoever hurt him most takes his halo. */
    private void awardHalo() {
        UUID top = damageBy.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (top == null) return;
        String name = Bukkit.getOfflinePlayer(top).getName();
        if (name == null) name = "someone";
        plugin.halo().award(top, name);
        plugin.godGear().reward(top, new java.util.HashSet<>(damageBy.keySet()));
        // everyone who fought him takes a Tear of God, for the Quantum Core
        for (UUID id : damageBy.keySet()) { Player p = Bukkit.getPlayer(id); if (p != null) plugin.quantum().give(p, QuantumGear.Mat.TEAR); }
        String n = name;
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.broadcast(Component.text(n + " struck God the hardest, and takes his Halo.", NamedTextColor.GOLD, TextDecoration.BOLD)), 100L);
    }

    // ---------------------------------------------------------------- tidying up

    /** Which part of his battle music fits this moment, or null when there's no fight (or he's still on his throne). */
    String musicState() {
        if (fightDragon == null) return null;
        EnderDragon d = dragon();
        if (d == null) return null;
        if (deathSaid || d.getPhase() == EnderDragon.Phase.DYING) return "end";
        if (peaceful || day < 1) return null;
        return day <= 2 ? "dawn" : day <= 5 ? "creation" : day == 6 ? "wrath" : "rest";
    }

    /** The middle of the fight: the main island of the End. */
    Location musicCentre() {
        EnderDragon d = dragon();
        return d == null ? null : new Location(d.getWorld(), 0, 64, 0);
    }

    private void endFight() {
        if (napping) { napping = false; if (snore != null) { snore.remove(); snore = null; } }
        if (seat != null) { seat.eject(); seat.remove(); seat = null; }
        for (Location l : thorns.keySet()) if (l.getBlock().getType() == Material.SWEET_BERRY_BUSH) l.getBlock().setType(Material.AIR, false);
        thorns.clear();
        clearSinners();
        clearAngels();
        for (org.bukkit.World ew : Bukkit.getWorlds())
            if (ew.getEnvironment() == org.bukkit.World.Environment.THE_END) plugin.archangels().clearNear(new Location(ew, 0, 64, 0), 260);
        marks.clear(); frozen.clear(); frozenUntil.clear(); saltImmune.clear(); greeted.clear(); damageBy.clear();
        fightDragon = null;
        peaceful = true;
        judged = lowSaid = deathSaid = hadFighters = idleSaid = false;
        day = 0; saltTicks = -1; nextAbility = 160; emptyTicks = 0; nearTicks = 0;
    }

    // ---------------------------------------------------------------- his voice

    private Component voice(String line) {
        return Component.text("God: ", NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.text(line, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
    }

    private void say(String line) {
        lastLine = System.currentTimeMillis();
        Component c = voice(line);
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Player p : w.getPlayers()) { p.sendMessage(c); p.playSound(p.getLocation(), "soulbound:god.speak", 0.6f, 1f); }
        }
    }

    private void tell(Player p, String line) {
        p.sendMessage(voice(line));
        p.playSound(p.getLocation(), "soulbound:god.speak", 0.6f, 1f);
    }

    private String pick(String... lines) { return lines[rnd.nextInt(lines.length)]; }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (fightDragon == null || peaceful || e.getEntity().getWorld().getEnvironment() != World.Environment.THE_END) return;
        say(pick("Go to your room.", "Well. That's what happens.", "Bless you. And goodbye.", "I did warn you."));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!sinners.contains(e.getPlayer().getUniqueId()) && e.getPlayer().isGlowing()) e.getPlayer().setGlowing(false);
    }

    // ---------------------------------------------------------------- his death: beams of light burst out of him

    private final List<BlockDisplay> rays = new ArrayList<>();
    private final List<Vector3f> rayDir = new ArrayList<>();
    private BukkitTask rayTask;

    /** The dragon's own death rays are hidden inside his giant body, so he gets his own: shafts of gold and white
     *  light that grow out of his chest and slowly turn, a rumble of blasts at the end, then the flash. */
    private void startDeathRays() {
        stopDeathRays();
        Entity g0 = godFigure();
        if (g0 == null) return;
        int count = 22;
        for (int i = 0; i < count; i++) {
            Vector3f dir = new Vector3f((float) rnd.nextGaussian(), (float) rnd.nextGaussian() * 0.8f + 0.15f, (float) rnd.nextGaussian()).normalize();
            rayDir.add(dir);
            Material m = i % 3 == 0 ? Material.YELLOW_STAINED_GLASS : i % 3 == 1 ? Material.WHITE_STAINED_GLASS : Material.ORANGE_STAINED_GLASS;
            BlockDisplay b = g0.getWorld().spawn(rayCentre(g0), BlockDisplay.class, d -> {
                d.setBlock(m.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(6f);
                d.setPersistent(false);
                d.addScoreboardTag(TAG + "_ray");
                d.setTransformation(ray(dir, 0.05f, 0.1f));
            });
            rays.add(b);
        }
        final int[] t = {0};
        rayTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            t[0] += 2;
            Entity g = godFigure();
            if (g == null || t[0] > 240) { stopDeathRays(); return; }
            Location c = rayCentre(g);
            float grow = Math.min(1f, t[0] / 170f);
            for (int i = 0; i < rays.size(); i++) {
                BlockDisplay b = rays.get(i);
                if (!b.isValid()) continue;
                Vector3f dir = rayDir.get(i);
                // each shaft swings slowly round, and flickers in length
                new Quaternionf().rotateY(0.012f * (i % 2 == 0 ? 1 : -1)).rotateX(0.006f).transform(dir).normalize();
                float len = (float) ((4 + 3.2 * scale) * grow * (0.75 + 0.25 * Math.sin(t[0] * 0.21 + i)));
                float w = (float) (0.25 + 0.06 * scale) * (0.6f + 0.4f * grow);
                b.setTeleportDuration(2);
                b.teleport(c);
                b.setInterpolationDelay(0);
                b.setInterpolationDuration(2);
                b.setTransformation(ray(dir, w, Math.max(0.1f, len)));
            }
            if (t[0] % 10 == 0) g.getWorld().playSound(c, "minecraft:block.beacon.ambient", 6f, 0.5f + grow);
            if (t[0] >= 150 && t[0] % 6 == 0) {   // the last few seconds: he comes apart in blasts of light
                Location at = c.clone().add(rnd.nextGaussian() * scale * 0.5, rnd.nextGaussian() * scale * 0.6, rnd.nextGaussian() * scale * 0.5);
                at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0, null, true);
                at.getWorld().spawnParticle(Particle.END_ROD, at, 40, scale * 0.3, scale * 0.3, scale * 0.3, 0.2, null, true);
                at.getWorld().playSound(at, "minecraft:entity.generic.explode", 4f, 0.6f + rnd.nextFloat() * 0.3f);
            }
        }, 2L, 2L);
    }

    private Location rayCentre(Entity g) { return g.getLocation().add(0, height() * 0.6, 0); }

    /** A shaft of light from the centre out along dir: a long thin glass block, rotated so its length points along dir. */
    private static Transformation ray(Vector3f dir, float w, float len) {
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), dir);
        Vector3f off = new Vector3f(-w / 2, -w / 2, 0);
        q.transform(off);
        return new Transformation(off, q, new Vector3f(w, w, len), new Quaternionf());
    }

    private void stopDeathRays() {
        if (rayTask != null) { rayTask.cancel(); rayTask = null; }
        for (BlockDisplay b : rays) if (b.isValid()) b.remove();
        rays.clear(); rayDir.clear();
    }

    private static void fx(Particle p, Location at, int n, double spread, double speed) {
        at.getWorld().spawnParticle(p, at, n, spread, spread, spread, speed, null, true);
    }
}
