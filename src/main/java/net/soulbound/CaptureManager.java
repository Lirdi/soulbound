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
    static final String PROP_TAG = "soulbound_captive_prop";
    static final String HOOD_TEAM = "sb_hooded";

    enum End { STRUGGLE, BROKEN, TIMEOUT, LET_GO, DEATH, QUIT, MOVED, SHUTDOWN, SACRIFICED, RESCUED }

    private final Soulbound plugin;
    private final LeashItem leash;
    private final Map<UUID, Capture> byCaptive = new HashMap<>();
    private final Map<UUID, Capture> byCaptor = new HashMap<>();
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    /** Teleports this plugin makes itself, so the teleport guard lets them through. */
    private final Set<UUID> ownTeleport = new HashSet<>();
    /** Dismounts this plugin makes itself (putting a carried captive down). */
    private final Set<UUID> ownDismount = new HashSet<>();
    private final Map<UUID, Long> dazedUntil = new HashMap<>();
    final AbductionItems items;
    private final org.bukkit.NamespacedKey gmKey;
    private double carryUp, carrySide, carryForward, propYaw, carryTurn;
    private org.bukkit.entity.Pose carryPose = org.bukkit.entity.Pose.SLEEPING;
    private BukkitTask task;
    private long tick;

    // settings
    private double maxHealth, captorBreakDamage, slack, snapDistance, spawnSafeRadius;
    private int durationTicks, strugglePresses, immunitySeconds, struggleDelayTicks, struggleGapTicks;
    private double struggleDecay, hoodedHarder, carriedHarder;
    private boolean requireLead, captorSlowness, broadcast, killOnLogout;
    private List<String> disabledWorlds = new ArrayList<>(), allowedCommands = new ArrayList<>();

    CaptureManager(Soulbound plugin, LeashItem leash) {
        this.plugin = plugin;
        this.leash = leash;
        this.items = new AbductionItems(plugin);
        this.gmKey = new org.bukkit.NamespacedKey(plugin, "carried_gamemode");
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        maxHealth = c.getDouble("capture.max-health", 6.0);
        durationTicks = Math.max(20, c.getInt("capture.duration-seconds", 180) * 20);
        requireLead = c.getBoolean("capture.require-holding-lead", true);
        captorBreakDamage = c.getDouble("capture.captor-break-damage", 6.0);
        strugglePresses = Math.max(1, c.getInt("capture.struggle-presses", 30));
        struggleDelayTicks = Math.max(0, c.getInt("capture.struggle-delay-seconds", 25)) * 20;
        struggleGapTicks = Math.max(1, (int) Math.round(20.0 / Math.max(0.5, c.getDouble("capture.struggle-max-per-second", 4))));
        struggleDecay = Math.max(0, c.getDouble("capture.struggle-decay-per-second", 1.5));
        hoodedHarder = Math.max(1, c.getDouble("capture.struggle-hooded-multiplier", 1.5));
        carriedHarder = Math.max(1, c.getDouble("capture.struggle-carried-multiplier", 1.25));
        slack = c.getDouble("capture.slack", 4.0);
        snapDistance = Math.max(slack + 1, c.getDouble("capture.snap-distance", 9.0));
        immunitySeconds = c.getInt("capture.immunity-seconds", 180);
        captorSlowness = c.getBoolean("capture.captor-slowness", true);
        broadcast = c.getBoolean("capture.broadcast", true);
        disabledWorlds = c.getStringList("safety.disabled-worlds");
        spawnSafeRadius = c.getDouble("safety.spawn-safe-radius", 48);
        killOnLogout = "kill".equalsIgnoreCase(c.getString("logout.captive", "kill"));
        carryUp = c.getDouble("capture.carry.height", 1.42);
        carrySide = c.getDouble("capture.carry.sideways", 0.0);
        carryForward = c.getDouble("capture.carry.forward", -0.12);
        carryTurn = c.getDouble("capture.carry.turn", 90);
        try { carryPose = org.bukkit.entity.Pose.valueOf(c.getString("capture.carry.pose", "SLEEPING").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { carryPose = org.bukkit.entity.Pose.SLEEPING; }
        propYaw = c.getDouble("capture.prop-yaw-offset", 180);
        allowedCommands = new ArrayList<>();
        for (String s : c.getStringList("capture.captive-allowed-commands")) allowedCommands.add(s.toLowerCase(java.util.Locale.ROOT));
    }

    void start() {
        for (World w : Bukkit.getWorlds()) for (Entity en : w.getEntities())
            if (en.getScoreboardTags().contains(ANCHOR_TAG) || en.getScoreboardTags().contains(PROP_TAG)) en.remove();
        items.registerRecipes();
        for (Player p : Bukkit.getOnlinePlayers()) { items.unlockFor(p); clearStaleHood(p); }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (Capture cap : new ArrayList<>(byCaptive.values())) end(cap, End.SHUTDOWN);
    }

    boolean isCaptive(Player p) { return byCaptive.containsKey(p.getUniqueId()); }
    Capture heldBy(Player captor) { return byCaptor.get(captor.getUniqueId()); }
    List<Capture> all() { return new ArrayList<>(byCaptive.values()); }
    boolean isActive(Capture cap) { return byCaptive.get(cap.captive) == cap; }

    /** Captors walking through a portal right now: their capture survives the change of world. */
    private final Set<UUID> portalCarry = new HashSet<>();

    /**
     * A player steps through a portal: take them to {@code to}, and if they're holding a captive, the captive comes
     * too, still bound (the lead's anchor is left behind and re-tied on the other side).
     */
    boolean carryThrough(Player captor, Location to) {
        Capture cap = byCaptor.get(captor.getUniqueId());
        boolean wasCarried = cap != null && cap.carried;
        if (wasCarried) stopCarry(cap, false);
        portalCarry.add(captor.getUniqueId());
        boolean ok;
        try { ok = captor.teleport(to); } finally { portalCarry.remove(captor.getUniqueId()); }
        if (!ok || cap == null || !isActive(cap)) return ok;
        Player victim = Bukkit.getPlayer(cap.captive);
        if (victim == null) return true;
        if (cap.anchor != null) { if (cap.anchor.isValid()) { victim.removePassenger(cap.anchor); cap.anchor.remove(); } cap.anchor = null; }
        Vector back = to.getDirection().setY(0);
        Location behind = to.clone();
        if (back.lengthSquared() > 1e-4) behind.subtract(back.normalize().multiply(1.5));
        teleportCaptive(cap, victim, behind);
        if (wasCarried) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player c2 = Bukkit.getPlayer(cap.captor), v2 = Bukkit.getPlayer(cap.captive);
            if (isActive(cap) && !cap.ritual && c2 != null && v2 != null) startCarry(cap, c2, v2);
        }, 2L);
        return true;
    }

    /** Where the captive's body really is: on the captor's shoulder when carried. */
    Location bodyAt(Capture cap, Player victim) {
        if (cap.carried) { Player c = Bukkit.getPlayer(cap.captor); if (c != null) return c.getLocation(); }
        return victim.getLocation();
    }

    /** Move a captive somewhere (the sacrifice uses this), past the teleport guard. */
    void moveCaptive(Player victim, Location to) {
        Capture cap = byCaptive.get(victim.getUniqueId());
        if (cap != null) teleportCaptive(cap, victim, to.clone());
    }

    // ---------------------------------------------------------------- starting a capture

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeadClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player victim)) return;
        Player captor = e.getPlayer();
        org.bukkit.inventory.ItemStack hand = captor.getInventory().getItemInMainHand();
        Capture held = byCaptive.get(victim.getUniqueId());
        // a friend cuts the chains and pulls the sack off
        if (hand.getType() == Material.SHEARS && held != null && !held.captor.equals(captor.getUniqueId()) && !byCaptive.containsKey(captor.getUniqueId())) {
            e.setCancelled(true);
            if (held.ritual) { captor.sendActionBar(Component.text("Too late for shears. Break the ritual!", NamedTextColor.RED)); return; }
            if (broadcast) Bukkit.broadcast(Component.text(captor.getName() + " cut " + victim.getName() + " free.", NamedTextColor.GREEN));
            end(held, End.RESCUED);
            sound(victim.getLocation(), "minecraft:entity.sheep.shear", 1f, 0.8f);
            return;
        }
        // the hood goes over a bound captive's head
        if (items.isHood(hand)) {
            e.setCancelled(true);
            if (held == null) { captor.sendActionBar(Component.text("Bind them first. Only a captive can be hooded.", NamedTextColor.RED)); return; }
            if (held.hooded) { captor.sendActionBar(Component.text(victim.getName() + " is already hooded.", NamedTextColor.GRAY)); return; }
            hood(held, victim);
            if (captor.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            return;
        }
        Capture own = byCaptor.get(captor.getUniqueId());
        if (own != null && own.captive.equals(victim.getUniqueId())) {
            e.setCancelled(true);
            if (captor.isSneaking() && justToggled(captor)) return;
            if (captor.isSneaking() && atPitEdge(captor)) throwIn(own, captor);
            else if (captor.isSneaking() && own.carried) putDown(own, captor);   // you clicked the one on your head
            else if (captor.isSneaking()) startCarry(own, captor, victim);
            else captor.sendActionBar(Component.text("Sneak and right-click to sling " + victim.getName() + " over your shoulder.", NamedTextColor.GRAY));
            return;
        }
        if (hand.getType() != Material.LEAD) return;
        if (!leash.captures(captor.getInventory().getItemInMainHand())) {
            if (e.getRightClicked() instanceof Player) captor.sendActionBar(Component.text("A plain lead won't hold a person. Craft a " + leashName() + ".", NamedTextColor.RED));
            return;
        }
        e.setCancelled(true);
        Capture mine = byCaptor.get(captor.getUniqueId());
        if (mine != null && mine.captive.equals(victim.getUniqueId())) {
            if (captor.isSneaking()) startCarry(mine, captor, victim);
            else captor.sendActionBar(Component.text("Sneak and right-click to sling " + victim.getName() + " over your shoulder.", NamedTextColor.GRAY));
            return;
        }
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
        if (immunitySeconds > 0 && until != null && until > System.currentTimeMillis()) return victim.getName() + " just escaped. Give it " + ((until - System.currentTimeMillis()) / 1000 + 1) + "s.";
        World w = victim.getWorld();
        if (disabledWorlds.contains(w.getName())) return "No captures in this world.";
        if (spawnSafeRadius > 0 && victim.getLocation().distance(w.getSpawnLocation()) < spawnSafeRadius) return "No captures this close to spawn.";
        double limit = isDazed(victim) ? maxHealth + 4 : maxHealth;
        if (victim.getHealth() > limit) return victim.getName() + " is too strong. Wear them down to " + hearts(maxHealth) + " first.";
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
        victim.getInventory().setChestplate(items.wornChains(victim.getInventory().getChestplate()));
        sound(victim.getLocation(), "minecraft:block.chain.place", 1f, 0.7f);
        captor.showTitle(Title.title(Component.empty(), Component.text("You captured " + victim.getName(), NamedTextColor.RED), t));
        sound(victim.getLocation(), "minecraft:entity.leash_knot.place", 1f, 0.6f);
        if (broadcast) Bukkit.broadcast(Component.text(captor.getName() + " has captured " + victim.getName() + ".", NamedTextColor.DARK_RED));
    }

    // ---------------------------------------------------------------- every tick

    private void tickAll() {
        tick++;
        if (tick % 20 == 0) tickSelfHooded();
        for (Capture cap : new ArrayList<>(byCaptive.values())) tickOne(cap);
    }

    private void tickOne(Capture cap) {
        Player captor = Bukkit.getPlayer(cap.captor), victim = Bukkit.getPlayer(cap.captive);
        if (captor == null || victim == null) { end(cap, End.QUIT); return; }
        if (captor.isDead() || victim.isDead()) { end(cap, End.DEATH); return; }
        if (!captor.getWorld().equals(victim.getWorld())) { end(cap, End.MOVED); return; }
        // the clock stands still at the temple and during the ritual, so the climb isn't a race against it
        if (cap.ritual || plugin.zones().inTemple(victim.getLocation())) cap.endsAt++;
        if (cap.ritual) {
            Rabbit ra = cap.anchor;
            if (ra != null && ra.isValid() && !ra.isLeashed()) ra.setLeashHolder(captor);
            return;
        }
        if (tick >= cap.endsAt) { end(cap, End.TIMEOUT); return; }
        if (requireLead && !hasLeash(captor)) { end(cap, End.LET_GO); return; }

        // stop struggling and the chains settle back: progress drains away
        if (cap.struggles > 0 && tick - cap.lastStruggleTick > 20 && struggleDecay > 0) cap.struggles = Math.max(0, cap.struggles - struggleDecay / 20.0);

        if (cap.carried) tickCarry(cap, captor, victim);
        else if (tick < cap.thrownUntil) steerIntoPit(victim);
        else drag(cap, captor, victim);

        if ((tick - cap.startedAt) % 10 == 0) {
            applyEffects(captor, victim);
            if (cap.hooded) victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, true, false, false));
            if (cap.hooded && (tick - cap.startedAt) % 40 == 0) hoodVision(victim);
            long left = Math.max(0, cap.endsAt - tick);
            cap.captorBar.progress(clamp((float) left / durationTicks));
            cap.captorBar.name(Component.text((cap.carried ? "Carrying " : "Holding ") + victim.getName() + "  ·  " + (left / 20) + "s"
                    + (cap.carried ? "  ·  sneak + right-click to put down" : "")));
            double total = pressesNeeded(cap);
            int need = (int) Math.ceil(Math.max(0, total - cap.struggles));
            cap.captiveBar.progress(clamp((float) (need / total)));
            long wait = cap.startedAt + struggleDelayTicks - tick;
            cap.captiveBar.name(Component.text((cap.hooded ? "Hooded" : "Captured by " + captor.getName()) + "  ·  "
                    + (wait > 0 ? "too tightly bound to struggle (" + (wait / 20 + 1) + "s)" : "tap sneak to struggle (" + need + ")")));
        }
    }

    private void drag(Capture cap, Player captor, Player victim) {
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
        Capture cap = byCaptor.get(captor.getUniqueId());
        if (captorSlowness && (cap == null || !cap.carried)) captor.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
        victim.setSprinting(false);
    }

    private void teleportCaptive(Capture cap, Player victim, Location to) {
        if (cap.carried) stopCarry(cap, false);
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
        if (cap.carried) stopCarry(cap, false);
        if (cap.hooded && victim != null) unhood(cap, victim);
        else if (victim != null) takeHoodOff(victim);   // the chains come off
        removeProps(cap);
        if (cap.anchor != null && cap.anchor.isValid()) cap.anchor.remove();
        if (captor != null) { captor.hideBossBar(cap.captorBar); if (captorSlowness) captor.removePotionEffect(PotionEffectType.SLOWNESS); }
        if (victim != null) {
            victim.hideBossBar(cap.captiveBar);
            victim.removePotionEffect(PotionEffectType.SLOWNESS);
            victim.removePotionEffect(PotionEffectType.WEAKNESS);
            victim.removePotionEffect(PotionEffectType.MINING_FATIGUE);
        }
        if (why != End.SHUTDOWN) immuneUntil.put(cap.captive, System.currentTimeMillis() + immunitySeconds * 1000L);
        if (captor != null && why != End.SHUTDOWN && leash.consumeOnCapture() && captor.getGameMode() != GameMode.CREATIVE) breakOneLeash(captor);
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
                if (captor != null) captor.sendActionBar(Component.text("You no longer have the chain. " + vn + " goes free.", NamedTextColor.GRAY));
            }
            case SACRIFICED -> { }
            case RESCUED -> {
                if (victim != null) {
                    victim.showTitle(Title.title(Component.text("Free!", NamedTextColor.GREEN, TextDecoration.BOLD), Component.text("Someone cut your chains.", NamedTextColor.GRAY)));
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1, true, false, true));
                }
                if (captor != null) captor.sendActionBar(Component.text(vn + " was cut free.", NamedTextColor.RED));
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
        if (cap == null || cap.ritual) return;
        struggle(cap, e.getPlayer());
    }

    /** Hooded, it's harder to fight what you can't see; slung over a shoulder, there's nothing to push against. */
    private double pressesNeeded(Capture cap) {
        return strugglePresses * (cap.hooded ? hoodedHarder : 1) * (cap.carried ? carriedHarder : 1);
    }

    private void struggle(Capture cap, Player p) {
        long wait = cap.startedAt + struggleDelayTicks - tick;
        if (wait > 0) {   // freshly bound: too tight to fight yet
            p.sendActionBar(Component.text("The chains are too tight. You can struggle in " + (wait / 20 + 1) + "s.", NamedTextColor.RED));
            return;
        }
        // at most a few tugs a second count, however fast the key is hammered
        if (tick - cap.lastStruggleTick < struggleGapTicks) return;
        cap.lastStruggleTick = tick;
        cap.struggles++;
        sound(bodyAt(cap, p), "minecraft:block.chain.step", 0.8f, 1.4f);
        if (cap.struggles >= pressesNeeded(cap)) end(cap, End.STRUGGLE);
    }

    // ---------------------------------------------------------------- what breaks it

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCaptorHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Capture cap = byCaptor.get(p.getUniqueId());
        if (cap == null) return;
        cap.captorDamage += e.getFinalDamage();
        if (cap.captorDamage < captorBreakDamage) return;
        if (cap.carried) {
            cap.captorDamage = 0;
            stopCarry(cap, true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, true, false, true));
            Player v = Bukkit.getPlayer(cap.captive);
            p.sendActionBar(Component.text("You stagger and drop " + (v != null ? v.getName() : "them") + "!", NamedTextColor.RED));
            sound(p.getLocation(), "minecraft:entity.player.attack.knockback", 1f, 0.6f);
            return;
        }
        end(cap, End.BROKEN);
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
        if (portalCarry.contains(e.getPlayer().getUniqueId())) return;
        if (cap != null && e.getFrom().getWorld() != null && e.getTo() != null
                && (!e.getFrom().getWorld().equals(e.getTo().getWorld()) || e.getFrom().distance(e.getTo()) > snapDistance)) end(cap, End.MOVED);
    }

    // ---------------------------------------------------------------- what a captive can't do

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) src = shooter;
        if (src instanceof Player p && byCaptive.containsKey(p.getUniqueId())) e.setCancelled(true);
        if (src instanceof Player p && carrying(p)) { e.setCancelled(true); p.sendActionBar(Component.text("Your hands are full.", NamedTextColor.GRAY)); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) { e.setCancelled(true); return; }
        Player p = e.getPlayer();
        if (!carrying(p)) return;
        e.setCancelled(true);   // hands full
        if (e.getHand() == EquipmentSlot.HAND && p.isSneaking()
                && (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) {
            Capture cap = byCaptor.get(p.getUniqueId());
            if (justToggled(p)) return;
            if (atPitEdge(p)) { throwIn(cap, p); return; }
            putDown(cap, p);
        }
    }

    /** Sneak + left-click (an empty-handed right-click at the air never reaches the server, a swing always does). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking() || !carrying(p) || justToggled(p)) return;
        Capture cap = byCaptor.get(p.getUniqueId());
        if (atPitEdge(p)) throwIn(cap, p);
        else putDown(cap, p);
    }

    private void putDown(Capture cap, Player captor) {
        stopCarry(cap, true);
        sound(captor.getLocation(), "minecraft:item.armor.equip_leather", 1f, 0.7f);
        captor.sendActionBar(Component.text("You put them down.", NamedTextColor.GRAY));
    }

    /** A click both picks up and swings the arm; don't let one click lift and drop in the same breath. */
    private final Map<UUID, Long> carryToggled = new HashMap<>();
    private boolean justToggled(Player p) {
        Long t = carryToggled.get(p.getUniqueId());
        return t != null && tick - t < 6;
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
        if (byCaptive.containsKey(e.getPlayer().getUniqueId()) || carrying(e.getPlayer())) e.setCancelled(true);
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

    /** Captives can only message, report and look things up: no teleports, no escape commands. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        if (!byCaptive.containsKey(e.getPlayer().getUniqueId())) return;
        String cmd = e.getMessage().substring(1).split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (allowedCommands.contains(cmd)) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("You're on a lead. You can only message, report or struggle.", NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- the abduction: cudgel, hood, carry

    /** Once they're chained, the leash only has to be on you somewhere, not in your hand: hands are free for the hood. */
    private boolean hasLeash(Player captor) {
        for (org.bukkit.inventory.ItemStack it : captor.getInventory().getContents()) if (leash.captures(it)) return true;
        return false;
    }

    boolean isDazed(Player p) { Long u = dazedUntil.get(p.getUniqueId()); return u != null && u > System.currentTimeMillis(); }
    private boolean carrying(Player p) { Capture c = byCaptor.get(p.getUniqueId()); return c != null && c.carried; }

    /** The cudgel: a weakened player struck from behind is dazed for a few seconds. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCudgel(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player hitter) || !(e.getEntity() instanceof Player victim)) return;
        if (!items.isCudgel(hitter.getInventory().getItemInMainHand()) || byCaptive.containsKey(victim.getUniqueId())) return;
        Vector toHitter = hitter.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        Vector facing = victim.getLocation().getDirection().setY(0);
        if (toHitter.lengthSquared() < 1e-4 || facing.lengthSquared() < 1e-4) return;
        if (toHitter.normalize().dot(facing.normalize()) > -0.2) return;                     // not from behind
        if (victim.getHealth() - e.getFinalDamage() > maxHealth + 4) return;                 // not weak enough
        dazedUntil.put(victim.getUniqueId(), System.currentTimeMillis() + 3000);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3, true, false, true));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 9, true, false, true));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 3, true, false, true));
        victim.setSprinting(false);
        victim.showTitle(Title.title(Component.empty(), Component.text("Dazed!", NamedTextColor.YELLOW, TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(400))));
        sound(victim.getLocation(), "minecraft:block.anvil.land", 0.5f, 1.9f);
        for (int i = 0; i < 6; i++) {
            int d = i * 10;
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (victim.isOnline()) victim.getWorld().spawnParticle(org.bukkit.Particle.WAX_OFF, victim.getEyeLocation().add(0, 0.6, 0), 6, 0.3, 0.1, 0.3, 0.2); }, d);
        }
    }

    private void hood(Capture cap, Player victim) {
        if (selfPrevList.containsKey(victim.getUniqueId())) selfUnhood(victim, Bukkit.getScoreboardManager().getMainScoreboard());
        cap.hooded = true;
        org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team old = sb.getEntryTeam(victim.getName());
        cap.prevTeam = old != null ? old.getName() : null;
        org.bukkit.scoreboard.Team t = sb.getTeam(HOOD_TEAM);
        if (t == null) { t = sb.registerNewTeam(HOOD_TEAM); t.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER); }
        t.addEntry(victim.getName());
        cap.prevListName = victim.playerListName();
        victim.playerListName(Component.text("Hooded figure", NamedTextColor.DARK_GRAY));
        victim.showTitle(Title.title(Component.text("Hooded", NamedTextColor.DARK_RED, TextDecoration.BOLD), Component.text("You can barely see. Nobody knows it's you.", NamedTextColor.GRAY)));
        sound(victim.getLocation(), "minecraft:item.armor.equip_leather", 1f, 0.6f);
        victim.getInventory().setHelmet(items.worn(victim.getInventory().getHelmet()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (cap.hooded && victim.isOnline()) hoodVision(victim); }, 50L);
        if (cap.carried && cap.body != null && cap.body.isValid()) cap.body.getEquipment().setHelmet(items.worn(null));
    }

    private void unhood(Capture cap, Player victim) {
        cap.hooded = false;
        org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team t = sb.getTeam(HOOD_TEAM);
        if (t != null) t.removeEntry(victim.getName());
        if (cap.prevTeam != null) { org.bukkit.scoreboard.Team old = sb.getTeam(cap.prevTeam); if (old != null) old.addEntry(victim.getName()); }
        victim.playerListName(cap.prevListName);
        victim.removePotionEffect(PotionEffectType.BLINDNESS);
        victim.clearTitle();
        takeHoodOff(victim);
    }

    /** The inside of the sack, over the whole screen (a title glyph): dark weave with pinpricks of light. Unlike
     *  blindness, it still works while they watch through their carrier's eyes. */
    private void hoodVision(Player p) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 4; c++) sb.append((char) (0xE300 + r * 4 + c)).append('\uE3F0');
            if (r == 0) sb.append('\uE3F1');
        }
        Component g = Component.text().content(sb.toString()).font(net.kyori.adventure.key.Key.key("soulbound", "fx"))
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.none()).build();
        p.showTitle(Title.title(g, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(2600), Duration.ofMillis(400))));
    }

    /** Off comes the sack; back on goes whatever helmet they were wearing. */
    private void takeHoodOff(Player p) {
        org.bukkit.inventory.ItemStack h = p.getInventory().getHelmet();
        if (items.isWorn(h)) p.getInventory().setHelmet(items.stashOf(h));
        org.bukkit.inventory.ItemStack c = p.getInventory().getChestplate();
        if (items.isWorn(c)) p.getInventory().setChestplate(items.stashOf(c));
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            org.bukkit.inventory.ItemStack it = p.getInventory().getItem(i);
            if (items.isWorn(it)) { org.bukkit.inventory.ItemStack st = items.stashOf(it); p.getInventory().setItem(i, st); }
        }
    }

    /** Hoods worn by choice: the wearer sees the inside of the sack and their name is hidden, until it comes off. */
    private final Map<UUID, String> selfPrevTeam = new HashMap<>();
    private final Map<UUID, Component> selfPrevList = new HashMap<>();
    private void tickSelfHooded() {
        org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Capture c = byCaptive.get(id);
            boolean captiveHood = c != null && c.hooded;
            org.bukkit.inventory.ItemStack h = p.getInventory().getHelmet();
            boolean wearing = !captiveHood && (items.isHood(h) || items.isWorn(h)) && p.getGameMode() != GameMode.SPECTATOR;
            boolean was = selfPrevList.containsKey(id);
            if (wearing && !was) {
                org.bukkit.scoreboard.Team old = sb.getEntryTeam(p.getName());
                selfPrevTeam.put(id, old != null && !old.getName().equals(HOOD_TEAM) ? old.getName() : null);
                selfPrevList.put(id, p.playerListName());
                org.bukkit.scoreboard.Team t = sb.getTeam(HOOD_TEAM);
                if (t == null) { t = sb.registerNewTeam(HOOD_TEAM); t.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER); }
                t.addEntry(p.getName());
                p.playerListName(Component.text("Hooded figure", NamedTextColor.DARK_GRAY));
                hoodVision(p);
            } else if (wearing) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, true, false, false));
                if (tick % 40 == 0) hoodVision(p);
            } else if (was) {
                selfUnhood(p, sb);
            }
        }
    }

    private void selfUnhood(Player p, org.bukkit.scoreboard.Scoreboard sb) {
        UUID id = p.getUniqueId();
        org.bukkit.scoreboard.Team t = sb.getTeam(HOOD_TEAM);
        if (t != null && !(byCaptive.get(id) != null && byCaptive.get(id).hooded)) t.removeEntry(p.getName());
        String prev = selfPrevTeam.remove(id);
        if (prev != null) { org.bukkit.scoreboard.Team old = sb.getTeam(prev); if (old != null) old.addEntry(p.getName()); }
        Component list = selfPrevList.remove(id);
        if (!(byCaptive.get(id) != null && byCaptive.get(id).hooded)) { p.playerListName(list); p.clearTitle(); p.removePotionEffect(PotionEffectType.BLINDNESS); }
    }

    @EventHandler
    public void onSelfHoodQuit(PlayerQuitEvent e) {
        if (selfPrevList.containsKey(e.getPlayer().getUniqueId())) selfUnhood(e.getPlayer(), Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** A hood left on by a crash: take it off when they come back. */
    private void clearStaleHood(Player p) {
        if (byCaptive.containsKey(p.getUniqueId())) return;
        org.bukkit.scoreboard.Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(HOOD_TEAM);
        if (t != null && t.hasEntry(p.getName())) t.removeEntry(p.getName());
        takeHoodOff(p);
        // carried when the server went down: out of spectator, back into the game mode they had
        String gm = p.getPersistentDataContainer().get(gmKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (gm != null) {
            p.getPersistentDataContainer().remove(gmKey);
            try { p.setGameMode(GameMode.valueOf(gm)); } catch (IllegalArgumentException ex) { p.setGameMode(GameMode.SURVIVAL); }
        }
    }

    /** A hooded captive who dies drops their own helmet, not the sack. */
    @EventHandler(priority = EventPriority.LOW)
    public void onHoodedDeath(PlayerDeathEvent e) {
        java.util.ListIterator<org.bukkit.inventory.ItemStack> it = e.getDrops().listIterator();
        while (it.hasNext()) {
            org.bukkit.inventory.ItemStack d = it.next();
            if (items.isWorn(d)) { org.bukkit.inventory.ItemStack st = items.stashOf(d); if (st != null) it.set(st); else it.remove(); }
        }
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) clearStaleHood(p); });
    }

    /** A captive can't touch their inventory: the hood stays on, nothing is passed or dropped. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInvClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && byCaptive.containsKey(p.getUniqueId())) e.setCancelled(true);
        else if (items.isWorn(e.getCurrentItem()) || items.isWorn(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInvDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && byCaptive.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId()) || items.isWorn(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (byCaptive.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /** A display that follows the captive: the shackles on their body, the hood on their head. */
    private org.bukkit.entity.ItemDisplay prop(Player victim, org.bukkit.inventory.ItemStack item, float scale) {
        return victim.getWorld().spawn(victim.getLocation(), org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(scale, scale, scale), new org.joml.AxisAngle4f()));
            d.setTeleportDuration(2);
            d.setPersistent(false);
            d.addScoreboardTag(PROP_TAG);
        });
    }

    private void tickProps(Capture cap, Player victim) {
        float body = victim.getBodyYaw() + (float) propYaw;
        if (cap.shackleShow == null || !cap.shackleShow.isValid()) cap.shackleShow = prop(victim, items.shackles(), 0.9375f);
        Location s = victim.getLocation().add(0, victim.isSneaking() ? 0.8 : 0.9375, 0);
        s.setYaw(body); s.setPitch(0);
        cap.shackleShow.teleport(s);
    }

    private void removeProps(Capture cap) {
        if (cap.shackleShow != null) { cap.shackleShow.remove(); cap.shackleShow = null; }
        if (cap.hoodShow != null) { cap.hoodShow.remove(); cap.hoodShow = null; }
    }

    /** Pick your captive up: they ride on your head, chained (and hooded, if you've hooded them). Sneaking is their struggle. */
    private void startCarry(Capture cap, Player captor, Player victim) {
        if (cap.carried || cap.ritual) return;
        if (cap.anchor != null && cap.anchor.isValid()) { victim.removePassenger(cap.anchor); cap.anchor.remove(); }
        cap.anchor = null;
        removeProps(cap);
        victim.setVelocity(new Vector());
        if (victim.isInsideVehicle()) victim.leaveVehicle();
        if (!captor.addPassenger(victim)) { captor.sendActionBar(Component.text("You can't lift them here.", NamedTextColor.RED)); return; }
        cap.carried = true;
        carryToggled.put(cap.captor, tick);
        cap.captorDamage = 0;
        captor.removePotionEffect(PotionEffectType.SLOWNESS);
        sound(captor.getLocation(), "minecraft:item.armor.equip_leather", 1f, 0.5f);
        victim.sendActionBar(Component.text(captor.getName() + " hoists you up. Tap sneak to struggle.", NamedTextColor.RED));
        captor.sendActionBar(Component.text("You hoist " + victim.getName() + " up. Sneak + click to put them down.", NamedTextColor.GRAY));
    }

    /** Put them down (or drop them): back on their feet beside you, chained, still on the lead. */
    private void stopCarry(Capture cap, boolean announce) {
        if (!cap.carried) return;
        cap.carried = false;
        carryToggled.put(cap.captor, tick);
        Player captor = Bukkit.getPlayer(cap.captor), victim = Bukkit.getPlayer(cap.captive);
        if (cap.body != null) { cap.body.remove(); cap.body = null; }
        if (victim == null) return;
        ownDismount.add(victim.getUniqueId());
        try { victim.leaveVehicle(); } finally { ownDismount.remove(victim.getUniqueId()); }
        if (captor != null) {
            Location down = captor.getLocation();
            Vector side = new Vector(Math.cos(Math.toRadians(down.getYaw())), 0, Math.sin(Math.toRadians(down.getYaw())));
            Location to = down.clone().add(side.multiply(0.6));
            if (!to.getBlock().isPassable() || !to.clone().add(0, 1, 0).getBlock().isPassable()) to = down;
            ownTeleport.add(victim.getUniqueId());
            try { victim.teleport(to); } finally { ownTeleport.remove(victim.getUniqueId()); }
            if (announce) victim.sendActionBar(Component.text("You hit the ground, still in chains.", NamedTextColor.RED));
        }
    }

    private void tickCarry(Capture cap, Player captor, Player victim) {
        if (!captor.getPassengers().contains(victim)) {
            if (victim.isInsideVehicle()) { ownDismount.add(victim.getUniqueId()); try { victim.leaveVehicle(); } finally { ownDismount.remove(victim.getUniqueId()); } }
            if (!captor.addPassenger(victim)) { stopCarry(cap, false); return; }
        }
        captor.setSprinting(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprint(org.bukkit.event.player.PlayerToggleSprintEvent e) {
        if (e.isSprinting() && carrying(e.getPlayer())) e.setCancelled(true);
    }

    /** Standing at the rim of Moloch's fire pit, at the top of the temple. */
    private boolean atPitEdge(Player p) {
        Zones z = plugin.zones();
        Location l = p.getLocation();
        if (!z.inRealm(l) || l.getY() < z.pitY + 6) return false;
        double dx = l.getX() - 0.5, dz = l.getZ() - 0.5;
        return dx * dx + dz * dz <= 5.5 * 5.5;
    }

    /** Heave them into the fire: off your shoulder (or off the end of the chain) and over the rim. */
    private void throwIn(Capture cap, Player captor) {
        Player victim = Bukkit.getPlayer(cap.captive);
        if (victim == null || cap.ritual) return;
        if (!cap.hooded && plugin.getConfig().getBoolean("sacrifice.require-hood", true)) {
            captor.sendActionBar(Component.text("Moloch won't take a bare face. Hood them first.", NamedTextColor.RED));
            sound(captor.getLocation(), "minecraft:block.fire.extinguish", 0.6f, 0.6f);
            return;
        }
        if (cap.carried) stopCarry(cap, false);
        else {
            Location at = captor.getLocation();
            ownTeleport.add(victim.getUniqueId());
            try { victim.teleport(at); } finally { ownTeleport.remove(victim.getUniqueId()); }
        }
        cap.thrownUntil = tick + 80;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!victim.isOnline()) return;
            Vector to = new Vector(0.5, 0, 0.5).subtract(victim.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 1e-4) to.normalize().multiply(Math.min(0.9, 0.25 + 0.12 * Math.sqrt(to.lengthSquared() + 0.001)));
            victim.setVelocity(to.setY(0.45));
        });
        sound(captor.getLocation(), "minecraft:entity.player.attack.sweep", 1f, 0.6f);
        sound(captor.getLocation(), "minecraft:block.chain.break", 1f, 0.6f);
        victim.sendActionBar(Component.text("You're thrown into the fire!", NamedTextColor.DARK_RED));
        captor.sendActionBar(Component.text("You heave " + victim.getName() + " into Moloch's fire.", NamedTextColor.DARK_RED));
    }

    /** A thrown captive drifts to the middle of the hole as they fall, so they don't snag on the rim. */
    private void steerIntoPit(Player victim) {
        Location l = victim.getLocation();
        if (!plugin.zones().inRealm(l)) return;
        double dx = 0.5 - l.getX(), dz = 0.5 - l.getZ();
        if (dx * dx + dz * dz > 7 * 7) return;
        Vector v = victim.getVelocity();
        v.setX(v.getX() * 0.6 + dx * 0.12);
        v.setZ(v.getZ() * 0.6 + dz * 0.12);
        victim.setVelocity(v);
    }

    /** A captive isn't hurt by being dragged about (falls, walls, water, cactus...), nor by the pit's flames: Moloch takes them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCaptiveHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !byCaptive.containsKey(p.getUniqueId())) return;
        String c = e.getCause().name();
        if (c.equals("FALL") || c.equals("SUFFOCATION") || c.equals("CRAMMING") || c.equals("FLY_INTO_WALL") || c.equals("DROWNING")
                || c.equals("CONTACT") || c.equals("FREEZE") || c.equals("HOT_FLOOR")) { e.setCancelled(true); if (c.equals("DROWNING")) p.setRemainingAir(p.getMaximumAir()); }
        else if ((c.contains("FIRE") || c.equals("LAVA") || c.equals("HOT_FLOOR")) && plugin.zones().inPit(p.getLocation())) { e.setCancelled(true); p.setFireTicks(0); }
    }

    /** Sneaking out of the carrier's eyes is just struggling: the view stays locked. */
    @EventHandler(ignoreCancelled = true)
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        Capture cap = byCaptive.get(e.getPlayer().getUniqueId());
        if (cap == null || !cap.carried) return;
        e.setCancelled(true);
        struggle(cap, e.getPlayer());
    }

    /** A carried captive can't simply hop off: sneaking is their struggle. */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Capture cap = byCaptive.get(p.getUniqueId());
        if (cap != null && cap.carried && !ownDismount.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPropHurt(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(PROP_TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        items.unlockFor(p);
        clearStaleHood(p);

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

    private String leashName() { return plugin.getConfig().getString("leash.name", "Cultist's Shackles"); }

    /** Each capture wears the leash down by one use, win or lose; it breaks when it has none left. */
    private void breakOneLeash(Player captor) {
        org.bukkit.inventory.PlayerInventory inv = captor.getInventory();
        org.bukkit.inventory.ItemStack[] slots = inv.getContents();
        int pick = -1;
        if (leash.captures(inv.getItemInMainHand())) pick = inv.getHeldItemSlot();
        else for (int i = 0; i < slots.length; i++) if (leash.captures(slots[i])) { pick = i; break; }
        if (pick < 0) return;
        org.bukkit.inventory.ItemStack it = inv.getItem(pick);
        if (it == null) return;
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm && dm.hasMaxDamage()) {
            int used = dm.getDamage() + 1, max = dm.getMaxDamage();
            if (used < max) {
                dm.setDamage(used);
                it.setItemMeta(dm);
                inv.setItem(pick, it);
                captor.sendActionBar(Component.text("Your " + leashName() + " wears. " + (max - used) + (max - used == 1 ? " use" : " uses") + " left.", NamedTextColor.GRAY));
                return;
            }
        }
        if (it.getAmount() <= 1) inv.setItem(pick, null); else it.setAmount(it.getAmount() - 1);
        captor.sendMessage(Component.text("Your " + leashName() + " is worn through and breaks.", NamedTextColor.GRAY));
        sound(captor.getLocation(), "minecraft:entity.item.break", 0.8f, 0.8f);
    }

    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }

    private static String hearts(double hp) {
        double h = hp / 2.0;
        return (h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h)) + (h == 1 ? " heart" : " hearts");
    }

    private static void sound(Location at, String key, float volume, float pitch) {
        if (at.getWorld() != null) at.getWorld().playSound(at, key, volume, pitch);
    }
}
