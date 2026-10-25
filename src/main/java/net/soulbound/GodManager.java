package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * God, in place of the ender dragon: a giant old man with a long grey beard, in white robes, flying (or standing)
 * where the dragon is. The resource pack makes the dragon itself invisible, gives it an old man's voice and calls it
 * God; this puts the figure in its place every tick, with a halo, and passes players' hits on his body to the dragon.
 * The dragon's own fight (crystals, perching, breath, the rays of light when it dies) carries on underneath.
 */
final class GodManager implements Listener {
    static final String TAG = "soulbound_god";
    private static final Particle.DustOptions HALO = new Particle.DustOptions(Color.fromRGB(255, 214, 90), 2.2f);

    private final Soulbound plugin;
    private final Map<UUID, UUID> godOf = new HashMap<>();   // dragon -> its God figure
    private final Map<UUID, Vector> heading = new HashMap<>();
    private final Map<UUID, Location> lastAt = new HashMap<>();
    private BukkitTask task;
    private int tick;

    private boolean enabled, flyingPose;
    private double scale, hitShare, flyBack, flyDown, standDown;

    // ---- the fight: his abilities, his judgement and his voice
    private final Store store;
    private final Random rnd = new Random();
    private double smiteDamage, napWake, sinnerDays;
    private int abilityGap, napSeconds;
    private UUID fightDragon;
    private boolean announced, judged, napped, lowSaid, deathSaid, napping, hadFighters;
    private int nextAbility = 200, napTicks, saltTicks = -1, emptyTicks;
    private double napDamage;
    private long lastLine, lastHitLine;
    private TextDisplay snore;
    private final List<Mark> marks = new ArrayList<>();
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Map<UUID, Integer> frozenUntil = new HashMap<>(), saltImmune = new HashMap<>();
    private final Set<UUID> sinners = new HashSet<>();
    private static final Particle.DustOptions BEAM = new Particle.DustOptions(Color.fromRGB(255, 236, 140), 2.4f);
    private static final Particle.DustOptions SALT = new Particle.DustOptions(Color.fromRGB(245, 245, 240), 1.6f);

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
        smiteDamage = c.getDouble("god.smite-damage", 6);
        abilityGap = Math.max(60, c.getInt("god.seconds-between-abilities", 11) * 20);
        napSeconds = Math.max(3, c.getInt("god.nap-seconds", 8));
        napWake = c.getDouble("god.nap-wake-damage", 30);
        sinnerDays = c.getDouble("god.sinner-days", 7);
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 1L); }

    void stop() {
        if (task != null) task.cancel();
        endFight();
        for (UUID id : godOf.values()) { Entity g = Bukkit.getEntity(id); if (g != null) g.remove(); }
        godOf.clear();
    }

    private double height() { return 1.8 * scale; }

    private void tickAll() {
        tick++;
        if (!enabled) return;
        if (tick % 40 == 1) scan();
        for (Iterator<Map.Entry<UUID, UUID>> it = godOf.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, UUID> e = it.next();
            Entity d = Bukkit.getEntity(e.getKey()), g = Bukkit.getEntity(e.getValue());
            if (!(d instanceof EnderDragon dragon) || !dragon.isValid()) {   // the dragon is gone: God fades into light
                if (g != null) {
                    fx(Particle.END_ROD, g.getLocation().add(0, height() * 0.5, 0), 200, scale * 0.5, 0.15);
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
                if (!g.getScoreboardTags().contains(TAG)) continue;
                if (!godOf.containsValue(g.getUniqueId())) g.remove();
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
        plugin.getLogger().info("God has appeared in " + l.getWorld().getName() + " (" + g.getType().getKey() + ").");
    }

    /** Summon a God figure at a spot, with the given tag. Null if the summon didn't work. */
    Entity summon(Location at, String tag) {
        String cmd = String.format(java.util.Locale.ROOT,
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
        return "God summoned in front of you (" + g.getType().getKey() + "). Remove him with /kill @e[tag=" + TAG + "_test]";
    }

    /** Keep God where the dragon is: flying face-forward through the sky, or standing upright when it perches or dies. */
    private void follow(EnderDragon d, Entity g) {
        Location dl = d.getLocation();
        UUID id = d.getUniqueId();
        Location prev = lastAt.put(id, dl.clone());
        Vector dir = heading.getOrDefault(id, new Vector(0, 0, 1));
        if (prev != null && prev.getWorld() == dl.getWorld()) {
            Vector v = dl.toVector().subtract(prev.toVector());
            if (v.lengthSquared() > 0.0025) dir = dir.multiply(0.85).add(v.normalize().multiply(0.15)).normalize();
        }
        heading.put(id, dir);
        EnderDragon.Phase ph = d.getPhase();
        boolean standing = napping || !flyingPose || switch (ph) {
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
            pitch = napping ? 55 : 0;   // nodding off
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

        // his halo, a ring of gold light above his head
        if (tick % 3 == 0) {
            Location head = standing ? to.clone().add(0, h * 1.02, 0)
                    : to.clone().add(dir.clone().multiply(h * 0.98)).add(0, h * 0.12, 0);
            double r = 0.32 * scale;
            for (int i = 0; i < 14; i++) {
                double a = i * Math.PI * 2 / 14 + tick * 0.05;
                g.getWorld().spawnParticle(Particle.DUST, head.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, HALO, true);
            }
        }
    }

    /** Hits on God's body are passed on to the dragon (at a share of their damage); nothing else touches him. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onGodHurt(EntityDamageEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(TAG)) return;
        e.setCancelled(true);
        if (!(e instanceof EntityDamageByEntityEvent ed)) return;
        Entity src = ed.getDamager();
        Player p = src instanceof Player pl ? pl : (src instanceof Projectile pr && pr.getShooter() instanceof Player sh ? sh : null);
        if (p == null) return;
        if (src instanceof Projectile) src.remove();
        EnderDragon d = dragonOf(e.getEntity().getUniqueId());
        if (d == null || !d.isValid() || d.getPhase() == EnderDragon.Phase.DYING || d.getHealth() <= 0) return;
        double amount = e.getDamage() * hitShare * (napping ? 2 : 1);
        onHurtBy(p, amount);
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
        Entity src = e.getDamager();
        Player p = src instanceof Player pl ? pl : (src instanceof Projectile pr && pr.getShooter() instanceof Player sh ? sh : null);
        if (p == null) return;
        if (napping) e.setDamage(e.getDamage() * 2);
        onHurtBy(p, e.getDamage());
    }

    private void onHurtBy(Player p, double amount) {
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

    // ---------------------------------------------------------------- the fight

    private List<Player> fighters(World w) {
        List<Player> out = new ArrayList<>();
        for (Player p : w.getPlayers())
            if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead()
                    && Math.hypot(p.getLocation().getX(), p.getLocation().getZ()) < 160) out.add(p);
        return out;
    }

    private void tickFight() {
        EnderDragon d = null; Entity g = null;
        for (Map.Entry<UUID, UUID> e : godOf.entrySet()) {
            if (Bukkit.getEntity(e.getKey()) instanceof EnderDragon dr && dr.isValid()) { d = dr; g = Bukkit.getEntity(e.getValue()); break; }
        }
        if (d == null || g == null) { if (fightDragon != null) endFight(); return; }
        if (!d.getUniqueId().equals(fightDragon)) { endFight(); fightDragon = d.getUniqueId(); }
        World w = d.getWorld();

        if (d.getPhase() == EnderDragon.Phase.DYING) {
            if (!deathSaid) {
                deathSaid = true;
                if (napping) wake(false);
                say("Well... I suppose you've grown up.");
                Bukkit.getScheduler().runTaskLater(plugin, () -> say("Don't tell Moloch."), 60L);
                clearSinners();
            }
            return;
        }
        List<Player> f = fighters(w);
        if (f.isEmpty()) {
            if (hadFighters && ++emptyTicks == 20 * 60) { say("Bedtime, everyone."); hadFighters = false; }
            return;
        }
        emptyTicks = 0;
        hadFighters = true;
        if (!announced) {
            announced = true;
            say("Right. Sit down. We need to have a talk.");
            nextAbility = 160;
        }
        double max = d.getAttribute(Attribute.MAX_HEALTH) != null ? d.getAttribute(Attribute.MAX_HEALTH).getValue() : 200;
        double r = d.getHealth() / max;
        if (!judged && r <= 0.75) judgement(f);
        if (!napped && r <= 0.5 && !napping && saltTicks < 0 && isFlying(d)) startNap(d, g);
        if (!lowSaid && r <= 0.2) {
            lowSaid = true;
            say("Fine. FINE. Back in my day the meek inherited the earth. Now they just hit me with swords.");
        }

        tickMarks(w, d);
        tickFrozen();
        if (saltTicks >= 0) tickSalt(f, g);
        if (napping) { tickNap(d, g); return; }
        if (--nextAbility <= 0) {
            if (saltTicks < 0 && rnd.nextInt(4) == 0) startSalt(f);
            else smite(f, r > 0.5 ? 1 : r > 0.25 ? 2 : 3, d);
            nextAbility = (int) (abilityGap * (r > 0.5 ? 1.0 : r > 0.25 ? 0.85 : 0.65));
        }
    }

    private static boolean isFlying(EnderDragon d) {
        return switch (d.getPhase()) {
            case CIRCLING, STRAFING, FLY_TO_PORTAL, CHARGE_PLAYER, LEAVE_PORTAL -> true;
            default -> false;
        };
    }

    // ---- Smite: a pillar of gold light finds you, then lightning comes down where it stands

    private void smite(List<Player> f, int count, EnderDragon d) {
        List<Player> order = new ArrayList<>(f);
        java.util.Collections.shuffle(order, rnd);
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
                double dmg = smiteDamage * (sinners.contains(q.getUniqueId()) ? 2 : 1);
                q.damage(dmg, d);
                q.setFireTicks(60);
            }
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
        if (saltTicks < 40) return;   // two seconds to look away
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

    // ---- Nap time: he dozes off mid-air. Double damage, but hit him too hard and he wakes up furious

    private void startNap(EnderDragon d, Entity g) {
        napped = true;
        napping = true;
        napTicks = 0;
        napDamage = 0;
        d.setPhase(EnderDragon.Phase.HOVER);
        say("I'm just... resting my eyes...");
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
        if (snore != null && snore.isValid()) {
            Location head = g.getLocation().add(0, height() * 1.15 + Math.sin(napTicks * 0.1) * 0.6, 0);
            snore.teleport(head);
        }
        if (napTicks % 30 == 0) g.getWorld().playSound(g.getLocation(), "minecraft:entity.fox.sleep", 6f, 0.5f);
        if (napTicks >= napSeconds * 20) wake(false);
    }

    private void wake(boolean furious) {
        if (!napping) return;
        napping = false;
        if (snore != null) { snore.remove(); snore = null; }
        EnderDragon d = fightDragon == null ? null : (Bukkit.getEntity(fightDragon) instanceof EnderDragon dr ? dr : null);
        if (d != null && d.isValid() && d.getPhase() != EnderDragon.Phase.DYING) d.setPhase(EnderDragon.Phase.CIRCLING);
        if (furious) {
            say("I WASN'T ASLEEP!");
            if (d != null) {
                List<Player> f = fighters(d.getWorld());
                smite(f, f.size(), d);   // everyone
            }
        } else say("Hm? Where was I? Oh yes. Smiting.");
        nextAbility = 60;
    }

    // ---- Judgement: God knows who's been feeding Moloch

    private void judgement(List<Player> f) {
        judged = true;
        say("Now. Who's been feeding that Moloch fellow?");
        long since = System.currentTimeMillis() - (long) (sinnerDays * 86_400_000L);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam("soulbound_sinners");
        if (team == null) { team = sb.registerNewTeam("soulbound_sinners"); team.color(NamedTextColor.RED); team.suffix(Component.text(" SINNER", NamedTextColor.DARK_RED, TextDecoration.BOLD)); }
        List<String> named = new ArrayList<>();
        for (Player p : f) {
            long last = store.data.getLong("sacrifice.last-offered-by." + p.getUniqueId(), 0);
            if (last < since) continue;
            sinners.add(p.getUniqueId());
            team.addEntry(p.getName());
            p.setGlowing(true);
            named.add(p.getName());
        }
        Team t = team;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (named.isEmpty()) say("Nobody? Hm. I'll find out.");
            else {
                for (String n : named) say("I can SEE you, " + n + ".");
                say("Sinners. You'll be smitten first. And harder.");
            }
        }, 50L);
        if (t.getEntries().isEmpty()) t.unregister();
    }

    private void clearSinners() {
        for (UUID id : sinners) { Player p = Bukkit.getPlayer(id); if (p != null) p.setGlowing(false); }
        sinners.clear();
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("soulbound_sinners");
        if (t != null) t.unregister();
    }

    private void endFight() {
        if (napping) { napping = false; if (snore != null) { snore.remove(); snore = null; } }
        clearSinners();
        marks.clear();
        frozen.clear();
        frozenUntil.clear();
        saltImmune.clear();
        fightDragon = null;
        announced = judged = napped = lowSaid = deathSaid = hadFighters = false;
        saltTicks = -1;
        nextAbility = 200;
        emptyTicks = 0;
    }

    // ---- his voice

    private void say(String line) {
        lastLine = System.currentTimeMillis();
        Component c = Component.text("God: ", NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.text(line, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Player p : w.getPlayers()) { p.sendMessage(c); p.playSound(p.getLocation(), "minecraft:entity.ender_dragon.ambient", 0.5f, 1f); }
        }
    }

    private String pick(String... lines) { return lines[rnd.nextInt(lines.length)]; }

    @EventHandler
    public void onEnterEnd(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld().getEnvironment() != World.Environment.THE_END || godOf.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.getWorld().getEnvironment() == World.Environment.THE_END)
                p.sendMessage(Component.text("God: ", NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.text("Oh. It's you lot.", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false)));
        }, 40L);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if (fightDragon == null || e.getEntity().getWorld().getEnvironment() != World.Environment.THE_END) return;
        say(pick("Go to your room.", "Well. That's what happens.", "Bless you. And goodbye.", "I did warn you."));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!sinners.contains(e.getPlayer().getUniqueId()) && e.getPlayer().isGlowing()) e.getPlayer().setGlowing(false);
    }

    private EnderDragon dragonOf(UUID god) {
        for (Map.Entry<UUID, UUID> e : godOf.entrySet())
            if (e.getValue().equals(god) && Bukkit.getEntity(e.getKey()) instanceof EnderDragon d) return d;
        return null;
    }

    private static void fx(Particle p, Location at, int n, double spread, double speed) {
        at.getWorld().spawnParticle(p, at, n, spread, spread, spread, speed, null, true);
    }
}
