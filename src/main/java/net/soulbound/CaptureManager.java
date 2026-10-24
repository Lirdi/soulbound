package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Capturing a weakened player with a lead: the leash, the drag, the struggle and every way it ends. */
final class CaptureManager implements Listener {
    static final String ANCHOR_TAG = "soulbound_anchor";

    enum End { STRUGGLE, BROKEN, TIMEOUT, LET_GO, DEATH, QUIT, MOVED, SHUTDOWN }

    private final Soulbound plugin;
    private final Map<UUID, Capture> byCaptive = new HashMap<>();
    private final Map<UUID, Capture> byCaptor = new HashMap<>();
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    /** Teleports this plugin makes itself, so the teleport guard lets them through. */
    private final Set<UUID> ownTeleport = new HashSet<>();
    private BukkitTask task;
    private long tick;

    // settings
    private double maxHealth, captorBreakDamage, slack, snapDistance, spawnSafeRadius;
    private int durationTicks, strugglePresses, immunitySeconds;
    private boolean requireLead, captorSlowness, broadcast, killOnLogout;
    private List<String> disabledWorlds = new ArrayList<>();

    CaptureManager(Soulbound plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        maxHealth = c.getDouble("capture.max-health", 6.0);
        durationTicks = Math.max(20, c.getInt("capture.duration-seconds", 180) * 20);
        requireLead = c.getBoolean("capture.require-holding-lead", true);
        captorBreakDamage = c.getDouble("capture.captor-break-damage", 6.0);
        strugglePresses = Math.max(1, c.getInt("capture.struggle-presses", 30));
        slack = c.getDouble("capture.slack", 4.0);
        snapDistance = Math.max(slack + 1, c.getDouble("capture.snap-distance", 9.0));
        immunitySeconds = c.getInt("capture.immunity-seconds", 300);
        captorSlowness = c.getBoolean("capture.captor-slowness", true);
        broadcast = c.getBoolean("capture.broadcast", true);
        disabledWorlds = c.getStringList("safety.disabled-worlds");
        spawnSafeRadius = c.getDouble("safety.spawn-safe-radius", 48);
        killOnLogout = "kill".equalsIgnoreCase(c.getString("logout.captive", "kill"));
    }

    void start() {
        for (World w : Bukkit.getWorlds()) for (Rabbit r : w.getEntitiesByClass(Rabbit.class)) if (r.getScoreboardTags().contains(ANCHOR_TAG)) r.remove();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Capture cap : new ArrayList<>(byCaptive.values())) end(cap, End.SHUTDOWN);
    }

    boolean isCaptive(Player p) { return byCaptive.containsKey(p.getUniqueId()); }
    Capture heldBy(Player captor) { return byCaptor.get(captor.getUniqueId()); }

    // ---------------------------------------------------------------- starting a capture

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeadClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player victim)) return;
        Player captor = e.getPlayer();
        if (captor.getInventory().getItemInMainHand().getType() != Material.LEAD) return;
        e.setCancelled(true);
        String refusal = whyNot(captor, victim);
        if (refusal != null) { captor.sendActionBar(Component.text(refusal, NamedTextColor.RED)); return; }
        begin(captor, victim);
    }

    private String whyNot(Player captor, Player victim) {
        if (!captor.hasPermission("soulbound.capture")) return "You can't capture players.";
        if (victim.hasPermission("soulbound.immune")) return victim.getName() + " can't be captured.";
        if (victim.getGameMode() != GameMode.SURVIVAL && victim.getGameMode() != GameMode.ADVENTURE) return victim.getName() + " can't be captured right now.";
        if (byCaptive.containsKey(captor.getUniqueId())) return "You're the one on a lead.";
        if (byCaptor.containsKey(captor.getUniqueId())) return "You're already holding someone.";
        if (byCaptive.containsKey(victim.getUniqueId())) return victim.getName() + " is already on a lead.";
        if (byCaptor.containsKey(victim.getUniqueId())) return victim.getName() + " is holding someone already.";
        Long until = immuneUntil.get(victim.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) return victim.getName() + " just escaped. Give it " + ((until - System.currentTimeMillis()) / 1000 + 1) + "s.";
        World w = victim.getWorld();
        if (disabledWorlds.contains(w.getName())) return "No captures in this world.";
        if (spawnSafeRadius > 0 && victim.getLocation().distance(w.getSpawnLocation()) < spawnSafeRadius) return "No captures this close to spawn.";
        if (victim.getHealth() > maxHealth) return victim.getName() + " is too strong. Wear them down to " + hearts(maxHealth) + " first.";
        return null;
    }

    private void begin(Player captor, Player victim) {
        long now = tick;
        BossBar captorBar = BossBar.bossBar(Component.text("Holding " + victim.getName()), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        BossBar captiveBar = BossBar.bossBar(Component.text("Captured by " + captor.getName()), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        Capture cap = new Capture(captor.getUniqueId(), victim.getUniqueId(), now, now + durationTicks, captorBar, captiveBar);
        byCaptive.put(victim.getUniqueId(), cap);
        byCaptor.put(captor.getUniqueId(), cap);
        captor.showBossBar(captorBar);
        victim.showBossBar(captiveBar);
        attachAnchor(cap, captor, victim);
        applyEffects(captor, victim);
        Title.Times t = Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(600));
        victim.showTitle(Title.title(Component.text("Captured", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("by " + captor.getName() + ". Tap sneak to struggle.", NamedTextColor.GRAY), t));
        captor.showTitle(Title.title(Component.empty(), Component.text("You captured " + victim.getName(), NamedTextColor.RED), t));
        sound(victim.getLocation(), "minecraft:entity.leash_knot.place", 1f, 0.6f);
        if (broadcast) Bukkit.broadcast(Component.text(captor.getName() + " has captured " + victim.getName() + ".", NamedTextColor.DARK_RED));
    }

    // ---------------------------------------------------------------- every tick

    private void tickAll() {
        tick++;
        for (Capture cap : new ArrayList<>(byCaptive.values())) tickOne(cap);
    }

    private void tickOne(Capture cap) {
        Player captor = Bukkit.getPlayer(cap.captor), victim = Bukkit.getPlayer(cap.captive);
        if (captor == null || victim == null) { end(cap, End.QUIT); return; }
        if (captor.isDead() || victim.isDead()) { end(cap, End.DEATH); return; }
        if (!captor.getWorld().equals(victim.getWorld())) { end(cap, End.MOVED); return; }
        if (tick >= cap.endsAt) { end(cap, End.TIMEOUT); return; }
        if (requireLead && captor.getInventory().getItemInMainHand().getType() != Material.LEAD
                && captor.getInventory().getItemInOffHand().getType() != Material.LEAD) { end(cap, End.LET_GO); return; }

        // the drag: free within the slack, pulled beyond it, snapped back if it gets silly
        Location cl = captor.getLocation(), vl = victim.getLocation();
        double d = cl.distance(vl);
        if (d > snapDistance) {
            Vector back = cl.getDirection().setY(0);
            Location to = cl.clone();
            if (back.lengthSquared() > 1e-4) to.subtract(back.normalize().multiply(1.5));   // just behind the captor
            teleportCaptive(cap, victim, to);
        } else if (d > slack) {
            Vector pull = cl.toVector().subtract(vl.toVector());
            double up = Math.max(-0.2, Math.min(0.45, pull.getY() * 0.25));
            pull.setY(0);
            if (pull.lengthSquared() > 1e-6) pull.normalize().multiply(Math.min(0.9, 0.18 + 0.12 * (d - slack)));
            Vector v = victim.getVelocity().multiply(0.4).add(pull);
            if (up > 0.05 && victim.isOnGround()) v.setY(Math.max(v.getY(), up));
            victim.setVelocity(v);
        }

        // keep the lead drawn
        Rabbit a = cap.anchor;
        if (a == null || !a.isValid()) attachAnchor(cap, captor, victim);
        else {
            if (!victim.getPassengers().contains(a)) { a.teleport(victim.getLocation().add(0, 1.8, 0)); victim.addPassenger(a); }
            if (!a.isLeashed() || !captor.equals(a.getLeashHolder())) a.setLeashHolder(captor);
        }

        if ((tick - cap.startedAt) % 10 == 0) {
            applyEffects(captor, victim);
            long left = Math.max(0, cap.endsAt - tick);
            cap.captorBar.progress(clamp((float) left / durationTicks));
            cap.captorBar.name(Component.text("Holding " + victim.getName() + "  ·  " + (left / 20) + "s"));
            int need = Math.max(0, strugglePresses - cap.struggles);
            cap.captiveBar.progress(clamp((float) need / strugglePresses));
            cap.captiveBar.name(Component.text("Captured by " + captor.getName() + "  ·  tap sneak to struggle (" + need + ")"));
        }
    }

    private void attachAnchor(Capture cap, Player captor, Player victim) {
        if (cap.anchor != null && cap.anchor.isValid()) cap.anchor.remove();
        Location at = victim.getLocation().add(0, 1.8, 0);
        Rabbit r = victim.getWorld().spawn(at, Rabbit.class, rb -> {
            rb.setInvisible(true);
            rb.setInvulnerable(true);
            rb.setSilent(true);
            rb.setAI(false);
            rb.setGravity(false);
            rb.setCollidable(false);
            rb.setPersistent(false);
            rb.setRemoveWhenFarAway(false);
            rb.setAdult();
            rb.addScoreboardTag(ANCHOR_TAG);
        });
        victim.addPassenger(r);
        r.setLeashHolder(captor);
        cap.anchor = r;
    }

    private void applyEffects(Player captor, Player victim) {
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 4, true, false, true));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 40, 2, true, false, true));
        if (captorSlowness) captor.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
        victim.setSprinting(false);
    }

    private void teleportCaptive(Capture cap, Player victim, Location to) {
        if (cap.anchor != null && cap.anchor.isValid()) victim.removePassenger(cap.anchor);
        to.setYaw(victim.getLocation().getYaw());
        to.setPitch(victim.getLocation().getPitch());
        ownTeleport.add(victim.getUniqueId());
        try { victim.teleport(to); } finally { ownTeleport.remove(victim.getUniqueId()); }
    }

    // ---------------------------------------------------------------- ending a capture

    void end(Capture cap, End why) {
        if (byCaptive.remove(cap.captive) == null) return;
        byCaptor.remove(cap.captor);
        Player captor = Bukkit.getPlayer(cap.captor), victim = Bukkit.getPlayer(cap.captive);
        if (cap.anchor != null && cap.anchor.isValid()) cap.anchor.remove();
        if (captor != null) { captor.hideBossBar(cap.captorBar); if (captorSlowness) captor.removePotionEffect(PotionEffectType.SLOWNESS); }
        if (victim != null) {
            victim.hideBossBar(cap.captiveBar);
            victim.removePotionEffect(PotionEffectType.SLOWNESS);
            victim.removePotionEffect(PotionEffectType.WEAKNESS);
            victim.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        }
        if (why != End.SHUTDOWN) immuneUntil.put(cap.captive, System.currentTimeMillis() + immunitySeconds * 1000L);
        String cn = captor != null ? captor.getName() : "their captor", vn = victim != null ? victim.getName() : "the captive";
        switch (why) {
            case STRUGGLE -> {
                if (victim != null) {
                    victim.showTitle(Title.title(Component.text("Free!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.text("You broke the lead.", NamedTextColor.GRAY)));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1, true, false, true));
                    sound(victim.getLocation(), "minecraft:entity.leash_knot.break", 1f, 0.7f);
                }
                if (captor != null) captor.sendActionBar(Component.text(vn + " broke free.", NamedTextColor.RED));
                if (broadcast) Bukkit.broadcast(Component.text(vn + " broke free from " + cn + ".", NamedTextColor.GREEN));
            }
            case BROKEN -> {
                if (victim != null) {
                    victim.showTitle(Title.title(Component.text("Free!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.text("The lead snapped.", NamedTextColor.GRAY)));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1, true, false, true));
                    sound(victim.getLocation(), "minecraft:entity.leash_knot.break", 1f, 0.7f);
                }
                if (captor != null) captor.sendActionBar(Component.text("You took too much damage. The lead snapped.", NamedTextColor.RED));
                if (broadcast) Bukkit.broadcast(Component.text(vn + " was freed from " + cn + ".", NamedTextColor.GREEN));
            }
            case TIMEOUT -> {
                if (victim != null) victim.sendActionBar(Component.text("The lead gives way. You're free.", NamedTextColor.GREEN));
                if (captor != null) captor.sendActionBar(Component.text("The lead gives way. " + vn + " is free.", NamedTextColor.GRAY));
            }
            case LET_GO -> {
                if (victim != null) victim.sendActionBar(Component.text(cn + " let you go.", NamedTextColor.GREEN));
                if (captor != null) captor.sendActionBar(Component.text("You let " + vn + " go.", NamedTextColor.GRAY));
            }
            case QUIT, DEATH, MOVED, SHUTDOWN -> {
                if (victim != null && why != End.SHUTDOWN) victim.sendActionBar(Component.text("The lead falls away.", NamedTextColor.GRAY));
                if (captor != null && why != End.SHUTDOWN) captor.sendActionBar(Component.text("The lead falls away.", NamedTextColor.GRAY));
            }
        }
    }

    // ---------------------------------------------------------------- the struggle

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Capture cap = byCaptive.get(e.getPlayer().getUniqueId());
        if (cap == null) return;
        cap.struggles++;
        sound(e.getPlayer().getLocation(), "minecraft:entity.item.break", 0.4f, 1.6f);
        if (cap.struggles >= strugglePresses) end(cap, End.STRUGGLE);
    }

    // ---------------------------------------------------------------- what breaks it

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCaptorHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Capture cap = byCaptor.get(p.getUniqueId());
        if (cap == null) return;
        cap.captorDamage += e.getFinalDamage();
        if (cap.captorDamage >= captorBreakDamage) end(cap, End.BROKEN);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        Capture cap = byCaptive.get(id);
        if (cap == null) cap = byCaptor.get(id);
        if (cap != null) end(cap, End.DEATH);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Capture held = byCaptive.get(p.getUniqueId());
        if (held != null) {
            end(held, End.QUIT);
            if (killOnLogout && !p.isDead()) {
                if (broadcast) Bukkit.broadcast(Component.text(p.getName() + " tried to escape by logging out, and paid for it.", NamedTextColor.DARK_RED));
                p.setHealth(0.0);
            }
            return;
        }
        Capture holding = byCaptor.get(p.getUniqueId());
        if (holding != null) end(holding, End.QUIT);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        String cause = e.getCause().name();
        boolean pearlOrChorus = cause.equals("ENDER_PEARL") || cause.contains("CHORUS") || cause.equals("CONSUMABLE_EFFECT");
        if (byCaptive.containsKey(p.getUniqueId())) {
            if (!ownTeleport.contains(p.getUniqueId())) e.setCancelled(true);
            return;
        }
        if (byCaptor.containsKey(p.getUniqueId()) && pearlOrChorus) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCaptorTeleported(PlayerTeleportEvent e) {
        Capture cap = byCaptor.get(e.getPlayer().getUniqueId());
        if (cap != null && e.getFrom().getWorld() != null && e.getTo() != null
                && (!e.getFrom().getWorld().equals(e.getTo().getWorld()) || e.getFrom().distance(e.getTo()) > snapDistance)) end(cap, End.MOVED);
    }

    // ---------------------------------------------------------------- what a captive can't do

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) src = shooter;
        if (src instanceof Player p && byCaptive.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player p && byCaptive.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!e.isGliding() || !(e.getEntity() instanceof Player p)) return;
        if (byCaptive.containsKey(p.getUniqueId()) || byCaptor.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- the invisible anchor

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnchorHurt(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ANCHOR_TAG)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnchorUnleashed(EntityUnleashEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ANCHOR_TAG)) e.setDropLeash(false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnchorClicked(PlayerUnleashEntityEvent e) {
        if (e.getEntity().getScoreboardTags().contains(ANCHOR_TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en.getScoreboardTags().contains(ANCHOR_TAG) && en instanceof Rabbit && !isLiveAnchor(en)) en.remove();
    }

    private boolean isLiveAnchor(Entity en) {
        for (Capture cap : byCaptive.values()) if (cap.anchor != null && cap.anchor.getUniqueId().equals(en.getUniqueId())) return true;
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    private static String hearts(double hp) {
        double h = hp / 2.0;
        return (h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h)) + (h == 1 ? " heart" : " hearts");
    }

    private static void sound(Location at, String key, float volume, float pitch) {
        if (at.getWorld() != null) at.getWorld().playSound(at, key, volume, pitch);
    }
}
