package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Ghast;
import org.bukkit.persistence.PersistentDataType;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shulkers in the End are Archangels: one hit from their bolt of judgement kills.
 * Once God has fallen, Archangels also drift through the Heavens as ghasts, appearing now and then out of sight
 * around players on the outer islands (never on the main island). Their fireball smites like a shulker's bolt.
 * They come and go like natural mobs: a few per player at most, and gone again once nobody is near.
 */
public final class Archangels implements Listener {
    private final Soulbound plugin;
    private final Set<UUID> judged = new HashSet<>();
    /** Where each Archangel ghast was sent, so it can be kept from drifting off across the sky. */
    private final java.util.Map<UUID, Location> home = new java.util.HashMap<>();

    private final NamespacedKey ghastKey;

    public Archangels(Soulbound plugin) {
        this.plugin = plugin;
        this.ghastKey = new NamespacedKey(plugin, "archangel_ghast");
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickGhasts, 200L, 100L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::lockOn, 40L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tether, 45L, 10L);
    }

    /**
     * Vanilla ghasts float off in random directions and never come back. Archangels are kept on a leash: one hunting a
     * player is drawn back once it strays too far from them, and one with nobody to hunt drifts back to where it came in.
     */
    private void tether() {
        double homeLeash = plugin.getConfig().getDouble("archangels.ghasts.leash", 16);
        double huntLeash = plugin.getConfig().getDouble("archangels.ghasts.leash-hunting", 22);
        home.keySet().removeIf(id -> { Entity e = plugin.getServer().getEntity(id); return e == null || !e.isValid(); });
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Ghast g : w.getEntitiesByClass(Ghast.class)) {
                if (!isArchangel(g)) continue;
                Location h = home.computeIfAbsent(g.getUniqueId(), k -> g.getLocation().clone());
                org.bukkit.entity.LivingEntity t = g.getTarget();
                Location goal; double leash;
                if (t != null && t.isValid() && t.getWorld().equals(w)) { goal = t.getLocation().add(0, 10, 0); leash = huntLeash; }
                else { goal = h; leash = homeLeash; }
                org.bukkit.util.Vector to = goal.toVector().subtract(g.getLocation().toVector());
                double dist = to.length();
                if (dist <= leash || dist < 0.01) continue;
                double speed = Math.min(0.6, 0.18 + (dist - leash) * 0.02);
                g.setVelocity(to.multiply(speed / dist));
            }
        }
    }

    /**
     * Vanilla ghasts only notice players within about 4 blocks of their own height, so an Archangel above or below you
     * would drift straight past. Every second, each one without a target picks the nearest player it can see.
     */
    private void lockOn() {
        double range = plugin.getConfig().getDouble("archangels.ghasts.sight", 48);
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Ghast g : w.getEntitiesByClass(Ghast.class)) {
                if (!isArchangel(g)) continue;
                org.bukkit.entity.LivingEntity cur = g.getTarget();
                if (cur instanceof Player sp && spared(sp)) g.setTarget(null);
                if (cur instanceof Player cp && cp.isValid() && !cp.isDead() && hunted(cp) && !spared(cp)
                        && cp.getLocation().distanceSquared(g.getLocation()) < range * range * 1.5) continue;
                Player best = null;
                double bd = range * range;
                for (Player p : w.getPlayers()) {
                    if (!hunted(p) || spared(p)) continue;
                    double d = p.getLocation().distanceSquared(g.getLocation());
                    if (d < bd && g.hasLineOfSight(p)) { bd = d; best = p; }
                }
                if (best != null) g.setTarget(best);
            }
        }
    }

    private boolean spared(Player p) { return plugin.grace() != null && plugin.grace().spared(p); }

    private static boolean hunted(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    boolean isArchangel(Entity e) { return e instanceof Ghast && e.getPersistentDataContainer().has(ghastKey, PersistentDataType.BYTE); }

    /** Every 5 seconds: clear away Archangels nobody is near, then maybe send one more towards each player. */
    private void tickGhasts() {
        FileConfiguration c = plugin.getConfig();
        boolean on = c.getBoolean("archangels.ghasts.enabled", true);
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (Ghast g : w.getEntitiesByClass(Ghast.class)) {
                if (!isArchangel(g)) continue;
                if (!on || nearestPlayer(g.getLocation(), 128) == null) g.remove();
            }
            if (!on || !plugin.store().data.getBoolean("god.defeated-once", false) || plugin.god().fighting()) continue;
            double minR = c.getDouble("archangels.ghasts.min-distance-from-centre", 800);
            int max = c.getInt("archangels.ghasts.max-per-player", 2);
            double chance = c.getDouble("archangels.ghasts.chance", 0.08);
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                Location l = p.getLocation();
                if (l.getX() * l.getX() + l.getZ() * l.getZ() < minR * minR) continue;
                if (ThreadLocalRandom.current().nextDouble() >= chance) continue;
                int near = 0;
                for (Entity e : p.getNearbyEntities(96, 96, 96)) if (isArchangel(e)) near++;
                if (near >= max) continue;
                Location at = spot(l);
                if (at == null) continue;
                summon(at);
            }
        }
    }

    /** An Archangel ghast at this spot (God's host on the third day uses this too). */
    Ghast summon(Location at) {
        return at.getWorld().spawn(at, Ghast.class, g -> {
            // no name tag: the death message already calls them Archangels
            double hp = Math.max(1, plugin.getConfig().getDouble("archangels.ghasts.health", 40));
            var mh = g.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (mh != null) { mh.setBaseValue(hp); g.setHealth(hp); }
            g.getPersistentDataContainer().set(ghastKey, PersistentDataType.BYTE, (byte) 1);
            home.put(g.getUniqueId(), at.clone());
        });
    }

    int countNear(Location l, double r) {
        int n = 0;
        for (Ghast g : l.getWorld().getEntitiesByClass(Ghast.class)) if (isArchangel(g) && g.getLocation().distanceSquared(l) < r * r) n++;
        return n;
    }

    void clearNear(Location l, double r) {
        for (Ghast g : l.getWorld().getEntitiesByClass(Ghast.class)) if (isArchangel(g) && g.getLocation().distanceSquared(l) < r * r) g.remove();
    }

    /** Somewhere open in the sky 40-70 blocks away and a little above, out of everyone's close view. */
    private Location spot(Location from) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int tries = 0; tries < 8; tries++) {
            double a = r.nextDouble(Math.PI * 2), d = 40 + r.nextDouble(30);
            Location at = from.clone().add(Math.cos(a) * d, 2 + r.nextDouble(10), Math.sin(a) * d);
            if (at.getY() > at.getWorld().getMaxHeight() - 8) continue;
            if (nearestPlayer(at, 24) != null) continue;
            boolean clear = true;
            for (int x = -2; x <= 2 && clear; x++) for (int y = 0; y <= 4 && clear; y++) for (int z = -2; z <= 2 && clear; z++)
                if (!at.clone().add(x, y, z).getBlock().isEmpty()) clear = false;
            if (clear) return at;
        }
        return null;
    }

    private static Player nearestPlayer(Location l, double within) {
        Player best = null;
        double bd = within * within;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Every horse in Heaven is white: wild ones, foals, and any hatched from an egg. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHorse(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Horse h) || h.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (!plugin.getConfig().getBoolean("heaven.white-horses", true)) return;
        h.setColor(org.bukkit.entity.Horse.Color.WHITE);
        h.setStyle(org.bukkit.entity.Horse.Style.NONE);
    }

    /** Ghasts killed in the End (the Archangels above all) sometimes leave a Dried Angel: a dried ghast, renamed by the pack. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onGhastDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Ghast g) || g.getWorld().getEnvironment() != World.Environment.THE_END) return;
        // their tears are an angel's tears (Nether ghasts' stay ghast tears)
        for (org.bukkit.inventory.ItemStack it : e.getDrops()) {
            if (it.getType() != org.bukkit.Material.GHAST_TEAR) continue;
            var m = it.getItemMeta();
            m.customName(Component.text("Angel Tear", NamedTextColor.WHITE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            m.lore(java.util.List.of(Component.text("Wept by an Archangel.", NamedTextColor.GRAY).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true)));
            it.setItemMeta(m);
        }
        if (g.getKiller() == null) return;
        double chance = plugin.getConfig().getDouble("archangels.ghasts.dried-angel-chance", 0.15);
        if (ThreadLocalRandom.current().nextDouble() < chance) e.getDrops().add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DRIED_GHAST));
    }

    /** A fireball knocked back into an Archangel doesn't kill it outright (vanilla does 1000 damage); it takes a set amount. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onReturnedFire(EntityDamageByEntityEvent e) {
        if (!isArchangel(e.getEntity()) || !(e.getDamager() instanceof Fireball)) return;
        double dmg = plugin.getConfig().getDouble("archangels.ghasts.returned-fireball-damage", 10);
        if (dmg >= 0) e.setDamage(dmg);
    }

    /** An Archangel's fireball smites, direct hit or blast. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireball(EntityDamageByEntityEvent e) {
        if (!on() || !(e.getEntity() instanceof Player p)) return;
        if (!(e.getDamager() instanceof Fireball fb) || !(fb.getShooter() instanceof Entity shooter) || !isArchangel(shooter)) return;
        if (spared(p)) { e.setCancelled(true); return; }   // Heaven knows its own: a stray blast passes through them
        smite(e, p);
    }

    private boolean on() { return plugin.getConfig().getBoolean("archangels.instant-kill", true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBolt(EntityDamageByEntityEvent e) {
        if (!on() || !(e.getDamager() instanceof ShulkerBullet) || !(e.getEntity() instanceof Player p)) return;
        if (p.getWorld().getEnvironment() != World.Environment.THE_END) return;
        smite(e, p);
    }

    private void smite(EntityDamageByEntityEvent e, Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        judged.add(p.getUniqueId());
        // More than any armour or enchantment can soak; a Totem of Undying still saves you if the config allows it.
        e.setDamage(1_000_000);
        if (!plugin.getConfig().getBoolean("archangels.totems-save", true)) {
            p.getInventory().remove(org.bukkit.Material.TOTEM_OF_UNDYING);
            if (p.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING)
                p.getInventory().setItemInOffHand(null);
        }
        p.getWorld().strikeLightningEffect(p.getLocation());
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 60, 0.4, 0.9, 0.4, 0.15);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 1.6f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> judged.remove(p.getUniqueId()), 5L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (!judged.remove(e.getPlayer().getUniqueId())) return;
        e.deathMessage(Component.text(e.getPlayer().getName() + " was judged by an Archangel", NamedTextColor.GOLD));
    }
}
