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
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Once Moloch is summoned, his hall is sealed. A burning seal fills the eight doorways: anyone may walk in,
 * nobody walks out until the fight is over, and the first stretch of each doorway drags you into the hall
 * so nobody can fight from the door. Whoever dies in the hall rises as a ghost: they drift through the hall
 * and watch, but they're held there until he falls (or the living all fall) unless they give up and leave.
 */
final class ArenaSeal implements Listener {
    private static final String KEPT = "moloch_kept";
    private static final Particle.DustOptions EMBER = new Particle.DustOptions(Color.fromRGB(255, 104, 24), 1.4f);
    private static final Particle.DustOptions GLOW = new Particle.DustOptions(Color.fromRGB(255, 196, 72), 0.9f);

    private final Soulbound plugin;
    private final Store store;
    private final Zones zones;
    private BukkitTask task;

    private boolean enabled, ghostsOn, keepDrops, blockCommands;
    private List<String> allowedCommands = List.of();
    private int fallenSeconds;

    /** Ghosts: player -> the fight they died in. */
    private final Map<UUID, Long> ghosts = new HashMap<>();
    private final Map<UUID, GameMode> ghostMode = new HashMap<>();
    /** Where each sealed player last stood inside the hall, to put them back if they slip out somehow. */
    private final Map<UUID, Location> lastIn = new HashMap<>();
    private final Map<UUID, Long> nagged = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();
    /** Who was in the hall when it sealed (or got in during the short grace): only they may be in it. */
    private final Set<UUID> members = new HashSet<>();
    /** Players whose flight we took away in the hall, to give back when they leave it or the fight ends. */
    private final Set<UUID> flightTaken = new HashSet<>();
    /** Where outsiders last stood outside the hall, to put them back if they get in anyway. */
    private final Map<UUID, Location> lastOut = new HashMap<>();
    private int sealedTicks, graceSeconds;
    private boolean wasSealed;
    private int tick, fallenTicks;

    ArenaSeal(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        this.zones = plugin.zones();
        loadSettings();
        var sec = store.data.getConfigurationSection("seal.ghosts");
        if (sec != null) for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                ghosts.put(id, sec.getLong(k + ".fight"));
                GameMode m = GameMode.SURVIVAL;
                try { m = GameMode.valueOf(sec.getString(k + ".mode", "SURVIVAL")); } catch (IllegalArgumentException ignored) {}
                ghostMode.put(id, m);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("seal.enabled", true);
        ghostsOn = c.getBoolean("seal.ghosts", true);
        keepDrops = c.getBoolean("seal.keep-drops", true);
        fallenSeconds = Math.max(0, c.getInt("seal.all-fallen-seconds", 60));
        blockCommands = c.getBoolean("seal.block-commands", true);
        graceSeconds = Math.max(0, c.getInt("seal.join-grace-seconds", 0));
        allowedCommands = c.getStringList("seal.allowed-commands").stream().map(x -> x.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim()).toList();
        if (!c.isList("seal.allowed-commands")) allowedCommands = List.of("moloch");
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L); }

    void stop() {
        if (task != null) task.cancel();
        for (UUID id : new HashSet<>(ghosts.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) release(p, false); }
        unkeepDrops();
        store.save();
    }

    private boolean sealed() { return enabled && plugin.boss().active(); }

    /** Past the grace, the hall is closed to anyone who wasn't in it. */
    private boolean closed() { return sealed() && sealedTicks >= graceSeconds * 20; }

    private boolean member(Player p) { return members.contains(p.getUniqueId()) || isGhost(p); }

    boolean isGhost(Player p) { return ghosts.containsKey(p.getUniqueId()); }

    private static boolean living(Player p) {
        return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    // ---------------------------------------------------------------- where things are

    /** Inside the hall proper: the doorway seal sits on its walls. */
    private boolean core(Location l) {
        if (l == null || !zones.inRealm(l)) return false;
        double dx = Math.abs(l.getX() - zones.arenaX), dz = Math.abs(l.getZ() - zones.arenaZ);
        return Math.max(dx, dz) <= zones.arenaRadius + 0.5 && l.getY() >= zones.arenaY - 3 && l.getY() <= 122 && zones.underRoof(l);
    }

    /** The first stretch of a doorway, just outside the seal. */
    private boolean threshold(Location l) { return zones.inArena(l) && !core(l); }

    /** Straight into the hall from a doorway: along the doorway's own axis. */
    private Vector inward(Location l) {
        double dx = l.getX() - zones.arenaX, dz = l.getZ() - zones.arenaZ;
        return Math.abs(dx) >= Math.abs(dz) ? new Vector(-Math.signum(dx), 0, 0) : new Vector(0, 0, -Math.signum(dz));
    }

    /** Where ghosts rise: floating over the hall floor, between a pillar and the south wall. */
    private Location ghostSpot() {
        Location c = zones.arenaCentre();
        if (c == null) return null;
        Location l = c.clone().add(0, 4, 27);
        l.setYaw(180f);
        l.setPitch(15f);
        return l;
    }

    /** Solid ground with room to stand, at or below this spot. */
    private Location floorAt(Location at) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int y = Math.min(at.getBlockY(), 121); y >= zones.arenaY - 4; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()
                    && !b.getRelative(0, 1, 0).isLiquid()) {
                Location out = new Location(w, x + 0.5, y + 1, z + 0.5, at.getYaw(), at.getPitch());
                return out;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the tick

    private void tick() {
        tick++;
        boolean on = sealed();
        if (on && !wasSealed) onSeal();
        if (!on && wasSealed) onUnseal();
        wasSealed = on;
        if (!on) {
            if (!ghosts.isEmpty() && tick % 20 == 0) for (UUID id : new HashSet<>(ghosts.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) release(p, true); }
            return;
        }
        World w = zones.world();
        if (w == null) return;
        long fight = plugin.boss().fightId();
        sealedTicks++;
        for (UUID id : new HashSet<>(flightTaken)) {   // flight back for anyone who has left the hall
            Player p = Bukkit.getPlayer(id);
            if (p == null) { flightTaken.remove(id); continue; }
            if (!zones.inArena(p.getLocation())) giveFlightBack(p);
        }

        int livingInHall = 0, ghostsHere = 0;
        for (Player p : w.getPlayers()) {
            UUID id = p.getUniqueId();
            Location l = p.getLocation();
            boolean ghost = isGhost(p);
            if (ghost && ghosts.get(id) != fight) { release(p, true); continue; }
            if (!ghost && !living(p)) { lastIn.remove(id); continue; }
            if (ghost) ghostsHere++;
            else if (zones.inArena(l) && member(p)) livingInHall++;

            if (!ghost && zones.inArena(l)) grounded(p);
            if (!ghost && !member(p)) {
                if (core(l) && !closed()) members.add(id);   // still in the grace: they're in
                else if (core(l)) { shutOut(p); continue; }
                else { if (!zones.inArena(l)) lastOut.put(id, l.clone()); if (threshold(l)) { pushOut(p, l); continue; } }
            }
            if (core(l)) { lastIn.put(id, l.clone()); }
            else {
                Location prev = lastIn.get(id);
                if (ghost) {
                    if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
                    Location back = prev != null ? prev : ghostSpot();
                    if (back != null) tp(p, back);
                    continue;
                }
                // slipped out without a move event (riding something, pushed): put them back
                if (prev != null && prev.getWorld() == l.getWorld() && prev.distanceSquared(l) < 64) {
                    if (p.isInsideVehicle()) p.leaveVehicle();
                    Location back = prev.clone(); back.setYaw(l.getYaw()); back.setPitch(l.getPitch());
                    tp(p, back);
                    repel(p, back);
                    continue;
                }
                lastIn.remove(id);
                // the threshold: the seal pulls you in, nobody fights from the door
                if (threshold(l)) {
                    Vector v = inward(l).multiply(0.34);
                    Vector cur = p.getVelocity();
                    p.setVelocity(new Vector(v.getX() != 0 ? v.getX() : cur.getX() * 0.5, Math.min(cur.getY(), 0.1), v.getZ() != 0 ? v.getZ() : cur.getZ() * 0.5));
                    if (tick % 4 == 0) w.spawnParticle(Particle.DUST, l.clone().add(0, 1, 0), 4, 0.4, 0.6, 0.4, 0, EMBER);
                    nag(p, "The seal drags you in. None leave until Moloch falls.", NamedTextColor.GOLD);
                } else if (approaching(l)) {
                    nag(p, "The hall is sealed. Step through and you fight until Moloch falls.", NamedTextColor.RED);
                }
            }
        }

        if (tick % 10 == 0) drawSeal(w);
        if (tick % 10 == 5) for (UUID id : ghosts.keySet()) {
            Player g = Bukkit.getPlayer(id);
            if (g != null && g.getWorld().equals(w)) w.spawnParticle(Particle.SOUL, g.getLocation().add(0, 1, 0), 2, 0.25, 0.4, 0.25, 0.01);
        }

        // nobody living left in the hall: give the stragglers a minute, then he has fed
        if (tick % 20 == 0) {
            if (fallenSeconds > 0 && ghostsHere > 0 && livingInHall == 0 && !plugin.boss().dying()) {
                fallenTicks++;
                int left = fallenSeconds - fallenTicks;
                if (left <= 0) { fallenTicks = 0; plugin.boss().allFallen(); }
                else if (left % 15 == 0 || left <= 5)
                    for (Player p : w.getPlayers()) p.sendActionBar(Component.text("No one living stands in Moloch's hall. He feeds in " + left + "s...", NamedTextColor.DARK_RED));
            } else fallenTicks = 0;
        }
    }

    /** No flying and no gliding in his hall: elytra fold, and /fly is taken away until you leave it or he falls. */
    private void grounded(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        if (p.isGliding()) { p.setGliding(false); nag(p, "The seal drags you out of the air.", NamedTextColor.GOLD); }
        if (p.getAllowFlight()) {
            p.setFlying(false);
            p.setAllowFlight(false);
            flightTaken.add(p.getUniqueId());
            nag(p, "The seal drags you out of the air. No flying in Moloch's hall.", NamedTextColor.GOLD);
        }
    }

    private void giveFlightBack(Player p) {
        if (flightTaken.remove(p.getUniqueId()) && p.isOnline()) p.setAllowFlight(true);
    }

    /** Someone who wasn't there when it sealed: back out they go. */
    private void shutOut(Player p) {
        Location out = lastOut.get(p.getUniqueId());
        if (out == null || zones.inArena(out)) out = outside();
        if (out != null) tp(p, out);
        nag(p, "The hall is sealed. The fight began without you.", NamedTextColor.RED);
    }

    private void pushOut(Player p, Location l) {
        Vector v = inward(l).multiply(-0.34);
        Vector cur = p.getVelocity();
        p.setVelocity(new Vector(v.getX() != 0 ? v.getX() : cur.getX() * 0.5, Math.min(cur.getY(), 0.1), v.getZ() != 0 ? v.getZ() : cur.getZ() * 0.5));
        if (tick % 4 == 0) l.getWorld().spawnParticle(Particle.DUST, l.clone().add(0, 1, 0), 4, 0.4, 0.6, 0.4, 0, EMBER);
        nag(p, "The hall is sealed. The fight began without you.", NamedTextColor.RED);
    }

    /** Somewhere safe outside the hall: just beyond the south doorway. */
    private Location outside() {
        Location c = zones.arenaCentre();
        if (c == null) return null;
        Location l = floorAt(c.clone().add(12, 4, zones.arenaRadius + 16));   // down the passage of a south doorway
        return l != null ? l : c.clone().add(12, 1, zones.arenaRadius + 16);
    }

    /** In a doorway, close enough to see the seal, but not yet at it. */
    private boolean approaching(Location l) {
        if (!zones.inRealm(l)) return false;
        double dx = Math.abs(l.getX() - zones.arenaX), dz = Math.abs(l.getZ() - zones.arenaZ), d = Math.max(dx, dz), g = Math.min(dx, dz);
        return g >= 9.5 && g <= 14.5 && d <= zones.arenaRadius + 40 && l.getY() >= zones.arenaY - 3 && l.getY() <= zones.arenaY + 6;
    }

    private void onSeal() {
        World w = zones.world();
        if (w == null) return;
        Location c = zones.arenaCentre();
        sealedTicks = 0;
        members.clear();
        for (Player p : w.getPlayers()) {
            if (core(p.getLocation())) {
                members.add(p.getUniqueId());
                lastIn.put(p.getUniqueId(), p.getLocation().clone());
                p.showTitle(Title.title(Component.text("THE HALL IS SEALED", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("None leave until Moloch falls", NamedTextColor.RED),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            } else p.sendMessage(Component.text(graceSeconds > 0
                    ? "Fire fills the doorways of Moloch's hall. " + graceSeconds + " seconds to get in; after that it's closed until he falls."
                    : "Fire fills the doorways of Moloch's hall. It's closed until he falls.", NamedTextColor.GOLD));
            if (c != null && p.getLocation().distanceSquared(c) < 120 * 120) p.playSound(p.getLocation(), "minecraft:block.respawn_anchor.charge", 1f, 0.5f);
        }
    }

    private void onUnseal() {
        lastIn.clear();
        members.clear();
        lastOut.clear();
        for (UUID id : new HashSet<>(flightTaken)) { Player p = Bukkit.getPlayer(id); if (p != null) giveFlightBack(p); }
        flightTaken.clear();
        fallenTicks = 0;
        for (UUID id : new HashSet<>(ghosts.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) release(p, true); }
        unkeepDrops();
        World w = zones.world();
        Location c = zones.arenaCentre();
        if (w != null && c != null) for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) < 120 * 120) {
            p.playSound(p.getLocation(), "minecraft:block.respawn_anchor.deplete", 1f, 0.6f);
            p.sendActionBar(Component.text("The seal on the doorways breaks.", NamedTextColor.GOLD));
        }
    }

    /** A sheet of embers across each of the eight doorways. */
    private void drawSeal(World w) {
        double R = zones.arenaRadius + 0.5, y0 = zones.arenaY - 1;
        int[] sides = {1, -1};
        for (int axis = 0; axis < 2; axis++) for (int s : sides) for (int o : sides) {
            for (double g = 9.7; g <= 14.3; g += 0.75) for (double y = 0.1; y <= 5.6; y += 0.75) {
                if (Math.random() > 0.45) continue;
                double x = axis == 0 ? zones.arenaX + s * R : zones.arenaX + o * g;
                double z = axis == 0 ? zones.arenaZ + o * g : zones.arenaZ + s * R;
                w.spawnParticle(Particle.DUST, x, y0 + y, z, 1, 0.05, 0.05, 0.05, 0, Math.random() < 0.25 ? GLOW : EMBER);
            }
        }
    }

    private void repel(Player p, Location at) {
        Vector v = inward(at).multiply(0.45);
        v.setY(0.15);
        if (p.getGameMode() != GameMode.SPECTATOR) p.setVelocity(v);
        Long last = nagged.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last == null || now - last > 1200) {
            at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 18, 0.5, 0.8, 0.5, 0, EMBER);
            at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 6, 0.4, 0.6, 0.4, 0.02);
            p.playSound(at, "minecraft:block.beacon.deactivate", 0.7f, 0.6f);
        }
        nag(p, isGhost(p) ? "The seal holds the dead too." : "The seal holds. None leave until Moloch falls.", NamedTextColor.GOLD);
    }

    private void nag(Player p, String msg, NamedTextColor colour) {
        long now = System.currentTimeMillis();
        Long last = nagged.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        nagged.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text(msg, colour));
    }

    private void tp(Player p, Location to) {
        bypass.add(p.getUniqueId());
        try { p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        finally { bypass.remove(p.getUniqueId()); }
    }

    // ---------------------------------------------------------------- the seal itself

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!sealed()) return;
        Player p = e.getPlayer();
        if (!living(p) && !isGhost(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (!isGhost(p) && !member(p) && closed() && !core(from) && core(to)) {   // outsiders can't step in
            Location stay = from.clone(); stay.setYaw(to.getYaw()); stay.setPitch(to.getPitch());
            e.setTo(stay);
            nag(p, "The hall is sealed. The fight began without you.", NamedTextColor.RED);
            return;
        }
        if (!core(from) || core(to)) return;
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
        repel(p, from);
    }

    /** No pearling, chorusing, /home-ing or spectator-hopping out of the hall. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!sealed()) return;
        Player p = e.getPlayer();
        if (bypass.contains(p.getUniqueId())) return;
        boolean ghost = isGhost(p);
        if (!ghost && !living(p)) return;
        if (!ghost && !member(p) && closed() && zones.inArena(e.getTo()) && !zones.inArena(e.getFrom())) {
            if (e.getCause().name().equals("COMMAND") && p.hasPermission("soulbound.admin")) return;
            e.setCancelled(true);
            p.sendMessage(Component.text("Moloch's hall is sealed. Nobody gets in until he falls.", NamedTextColor.RED));
            return;
        }
        if (!core(e.getFrom()) || core(e.getTo())) return;
        String cause = e.getCause().name();
        if (!ghost) {
            if (cause.equals("PLUGIN") || cause.equals("UNKNOWN") || cause.equals("DISMOUNT")) return;
            if (cause.equals("COMMAND") && p.hasPermission("soulbound.admin")) return;
        } else if (p.hasPermission("soulbound.admin") && cause.equals("COMMAND")) return;
        e.setCancelled(true);
        repel(p, e.getFrom());
    }

    /** No commands inside the sealed hall (or as a ghost), except the few on the allowed list. Admins are exempt. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!blockCommands || !sealed()) return;
        Player p = e.getPlayer();
        if (p.hasPermission("soulbound.admin")) return;
        boolean ghost = isGhost(p);
        if (!ghost && !(living(p) && zones.inArena(p.getLocation()))) return;
        String cmd = e.getMessage().substring(1).toLowerCase(Locale.ROOT).trim();
        int colon = cmd.indexOf(':'), space = cmd.indexOf(' ');
        if (colon >= 0 && (space < 0 || colon < space)) cmd = cmd.substring(colon + 1);   // soulbound:moloch -> moloch
        for (String ok : allowedCommands) if (cmd.equals(ok) || cmd.startsWith(ok + " ")) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("Moloch's seal smothers your words. No commands in the hall until the fight is over.", NamedTextColor.RED));
        if (ghost) tellLeave(p);
    }

    /** No elytra in the hall while he's awake. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(org.bukkit.event.entity.EntityToggleGlideEvent e) {
        if (!e.isGliding() || !sealed() || !(e.getEntity() instanceof Player p) || !zones.inArena(p.getLocation())) return;
        e.setCancelled(true);
        nag(p, "The seal drags you out of the air.", NamedTextColor.GOLD);
    }

    /** Fighting from outside the seal doesn't work: step in or stay out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!sealed() || !plugin.boss().isFoe(e.getEntity())) return;
        Entity src = e.getDamager();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity sh) src = sh;
        if (!(src instanceof Player p) || core(p.getLocation())) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Projectile pr) pr.remove();
        nag(p, "The seal swallows your attack. Step inside to fight him.", NamedTextColor.GOLD);
    }

    // ---------------------------------------------------------------- ghosts

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        lastIn.remove(p.getUniqueId());
        if (!ghostsOn || !sealed() || !zones.inArena(p.getLocation())) return;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return;
        UUID id = p.getUniqueId();
        ghosts.put(id, plugin.boss().fightId());
        ghostMode.put(id, p.getGameMode());
        store.set("seal.ghosts." + id + ".fight", plugin.boss().fightId());
        store.set("seal.ghosts." + id + ".mode", p.getGameMode().name());
        store.save();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!isGhost(p)) return;
        if (!sealed() || ghosts.get(p.getUniqueId()) != plugin.boss().fightId()) { forget(p.getUniqueId()); return; }
        Location spot = ghostSpot();
        if (spot == null) return;
        e.setRespawnLocation(spot);
        Bukkit.getScheduler().runTaskLater(plugin, () -> becomeGhost(p), 1L);
    }

    private void becomeGhost(Player p) {
        if (!p.isOnline() || !isGhost(p)) return;
        p.setGameMode(GameMode.SPECTATOR);
        Location spot = ghostSpot();
        if (spot != null && !core(p.getLocation())) tp(p, spot);
        lastIn.put(p.getUniqueId(), p.getLocation().clone());
        p.showTitle(Title.title(Component.text("YOU HAVE FALLEN", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                Component.text("Your spirit is bound to the hall until Moloch falls", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(1000))));
        p.playSound(p.getLocation(), "minecraft:particle.soul_escape", 1f, 0.7f);
        tellLeave(p);
    }

    private void tellLeave(Player p) {
        p.sendMessage(Component.text("You're a ghost now. Watch the fight; you'll rise in the hall when it ends, next to your things. ", NamedTextColor.GRAY)
                .append(Component.text("[Leave the temple]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/moloch leave"))
                        .hoverEvent(HoverEvent.showText(Component.text("Give up the fight and return to the living, at your spawn point.", NamedTextColor.GRAY)))));
    }

    /** /moloch leave: a ghost gives up and returns to the living, at their spawn. */
    void leave(Player p) {
        if (!isGhost(p)) { p.sendMessage(Component.text("Only the fallen can leave that way.", NamedTextColor.GRAY)); return; }
        UUID id = p.getUniqueId();
        GameMode mode = ghostMode.getOrDefault(id, GameMode.SURVIVAL);
        forget(id);
        lastIn.remove(id);
        Location to = p.getRespawnLocation();
        if (to == null || zones.inTemple(to)) to = Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
        tp(p, to);
        p.setGameMode(mode);
        p.showTitle(Title.title(Component.empty(), Component.text("You turn away from the fire", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(700))));
    }

    /** The fight is over: the ghost rises where it floats (on the floor below), or at the hall's edge. */
    private void release(Player p, boolean announce) {
        UUID id = p.getUniqueId();
        GameMode mode = ghostMode.getOrDefault(id, GameMode.SURVIVAL);
        forget(id);
        lastIn.remove(id);
        if (p.isDead()) return;
        if (zones.inRealm(p.getLocation())) {
            Location here = core(p.getLocation()) ? floorAt(p.getLocation()) : null;
            if (here == null) { Location s = ghostSpot(); here = s != null ? floorAt(s) : null; if (here == null) here = s; }
            if (here != null) { here.setYaw(p.getLocation().getYaw()); here.setPitch(p.getLocation().getPitch()); tp(p, here); }
        }
        if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(mode);
        p.setFallDistance(0);
        if (announce) {
            p.showTitle(Title.title(Component.text("YOU LIVE", NamedTextColor.GOLD, TextDecoration.BOLD), Component.text("The hall releases its dead", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), "minecraft:item.totem.use", 0.6f, 1.2f);
        }
    }

    private void forget(UUID id) {
        ghosts.remove(id);
        ghostMode.remove(id);
        store.set("seal.ghosts." + id, null);
        store.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!isGhost(p)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !isGhost(p)) return;
            if (sealed() && ghosts.get(p.getUniqueId()) == plugin.boss().fightId()) {
                if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
                if (!core(p.getLocation())) { Location s = ghostSpot(); if (s != null) tp(p, s); }
                tellLeave(p);
            } else release(p, true);
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastIn.remove(e.getPlayer().getUniqueId()); nagged.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { lastIn.remove(e.getPlayer().getUniqueId()); }

    // ---------------------------------------------------------------- what the dead leave behind

    /** Things dropped in the hall during the fight don't burn or despawn, so the dead can pick them up afterwards. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        if (!keepDrops || !sealed() || !core(e.getLocation())) return;
        Item it = e.getEntity();
        it.setUnlimitedLifetime(true);
        it.setInvulnerable(true);
        it.addScoreboardTag(KEPT);
    }

    /** Once it's over, kept items get the normal five minutes again. */
    private void unkeepDrops() {
        World w = zones.world();
        if (w == null) return;
        for (Item it : w.getEntitiesByClass(Item.class)) if (it.getScoreboardTags().contains(KEPT)) unkeep(it);
    }

    private static void unkeep(Item it) {
        it.removeScoreboardTag(KEPT);
        it.setUnlimitedLifetime(false);
        it.setInvulnerable(false);
        it.setTicksLived(1);
    }

    @EventHandler
    public void onEntities(EntitiesLoadEvent e) {
        if (sealed()) return;
        for (Entity en : e.getEntities()) if (en instanceof Item it && it.getScoreboardTags().contains(KEPT)) unkeep(it);
    }
}
