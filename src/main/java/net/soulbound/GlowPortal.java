package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A glowstone portal, like the Aether's: build a frame of glowstone (inside at least 2 wide and 3 tall, at most 21 by
 * 21, upright, corners optional) and pour a water bucket into it. The inside fills with End gateway blocks.
 * Breaking any of the frame's glowstone closes it again.
 *
 * Testing only for now: the gateways don't take you anywhere (entering them is cancelled, which also stops the game
 * from building an exit gateway out in the End).
 */
final class GlowPortal implements Listener {
    private static final int MAX = 21;
    private final Soulbound plugin;
    /** Each portal: world, inside min corner, axis ("x" = it runs along x, "z" = along z), width, height. */
    private record Portal(String world, int x, int y, int z, boolean alongX, int w, int h) {
        boolean inside(Block b) {
            if (!b.getWorld().getName().equals(world)) return false;
            int along = alongX ? b.getX() - x : b.getZ() - z, flat = alongX ? b.getZ() - z : b.getX() - x;
            return flat == 0 && along >= 0 && along < w && b.getY() >= y && b.getY() < y + h;
        }
        boolean onFrame(Block b) {
            if (!b.getWorld().getName().equals(world)) return false;
            int along = alongX ? b.getX() - x : b.getZ() - z, flat = alongX ? b.getZ() - z : b.getX() - x, up = b.getY() - y;
            if (flat != 0) return false;
            boolean sides = (along == -1 || along == w) && up >= 0 && up < h;
            boolean ends = (up == -1 || up == h) && along >= 0 && along < w;
            return sides || ends;
        }
        Block at(World wd, int along, int up) { return alongX ? wd.getBlockAt(x + along, y + up, z) : wd.getBlockAt(x, y + up, z + along); }
        String save() { return world + "," + x + "," + y + "," + z + "," + (alongX ? "x" : "z") + "," + w + "," + h; }
        static Portal load(String s) {
            String[] p = s.split(",");
            return new Portal(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4].equals("x"), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        }
    }
    private final List<Portal> portals = new ArrayList<>();

    GlowPortal(Soulbound plugin) {
        this.plugin = plugin;
        for (String s : plugin.store().data.getStringList("glow-portals")) {
            try { portals.add(Portal.load(s)); } catch (RuntimeException ignored) { }
        }
    }

    private boolean on() { return plugin.getConfig().getBoolean("glow-portal.enabled", true); }

    void start() {
        // stop the gateways doing anything when walked into (Paper's EntityInsideBlockEvent, looked up by name)
        try {
            Class<? extends Event> ev = Class.forName("io.papermc.paper.event.entity.EntityInsideBlockEvent").asSubclass(Event.class);
            var getBlock = ev.getMethod("getBlock");
            Bukkit.getPluginManager().registerEvent(ev, this, EventPriority.HIGH, (l, e) -> {
                if (!ev.isInstance(e)) return;
                try {
                    Block b = (Block) getBlock.invoke(e);
                    if (b.getType() == Material.END_GATEWAY && portalAt(b) != null) ((org.bukkit.event.Cancellable) e).setCancelled(true);
                } catch (ReflectiveOperationException ignored) { }
            }, plugin);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Glowstone portals: can't stop entering them on this server build; they'll act like normal End gateways.");
        }
    }

    private void save() {
        plugin.store().set("glow-portals", portals.stream().map(Portal::save).toList());
        plugin.store().save();
    }

    private Portal portalAt(Block b) {
        for (Portal p : portals) if (p.inside(b)) return p;
        return null;
    }

    // ---------------------------------------------------------------- lighting it

    private static boolean open(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR || m == Material.WATER;
    }

    /** Flood the plane from the start block; a portal if it's a full rectangle walled by glowstone. */
    private static Portal find(Block start, boolean alongX) {
        if (!open(start.getType())) return null;
        World w = start.getWorld();
        Set<Long> seen = new HashSet<>();
        Deque<int[]> q = new ArrayDeque<>();
        int ox = start.getX(), oy = start.getY(), oz = start.getZ();
        q.add(new int[]{0, 0});
        seen.add(0L);
        int minA = 0, maxA = 0, minU = 0, maxU = 0, count = 0;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            count++;
            if (count > MAX * MAX) return null;
            minA = Math.min(minA, c[0]); maxA = Math.max(maxA, c[0]); minU = Math.min(minU, c[1]); maxU = Math.max(maxU, c[1]);
            if (maxA - minA >= MAX || maxU - minU >= MAX) return null;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int a = c[0] + d[0], u = c[1] + d[1];
                long key = ((long) a << 32) ^ (u & 0xffffffffL);
                if (seen.contains(key)) continue;
                int y = oy + u;
                if (y < w.getMinHeight() || y >= w.getMaxHeight()) return null;
                Block nb = alongX ? w.getBlockAt(ox + a, y, oz) : w.getBlockAt(ox, y, oz + a);
                Material m = nb.getType();
                if (m == Material.GLOWSTONE) continue;
                if (!open(m)) return null;
                seen.add(key);
                q.add(new int[]{a, u});
            }
        }
        int wd = maxA - minA + 1, ht = maxU - minU + 1;
        if (count != wd * ht || wd < 2 || ht < 3) return null;
        return alongX ? new Portal(w.getName(), ox + minA, oy + minU, oz, true, wd, ht)
                      : new Portal(w.getName(), ox, oy + minU, oz + minA, false, wd, ht);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPour(PlayerInteractEvent e) {
        if (!on() || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        ItemStack held = e.getItem();
        if (held == null || held.getType() != Material.WATER_BUCKET) return;
        Block target = e.getClickedBlock().getRelative(e.getBlockFace());
        if (e.useItemInHand() == Event.Result.DENY) return;
        Portal found = find(target, true);
        if (found == null) found = find(target, false);
        if (found == null) return;
        final Portal p = found;
        e.setCancelled(true);
        World w = target.getWorld();
        for (int a = 0; a < p.w(); a++) for (int u = 0; u < p.h(); u++) p.at(w, a, u).setType(Material.END_GATEWAY, false);
        portals.removeIf(o -> o.world().equals(p.world()) && o.x() == p.x() && o.y() == p.y() && o.z() == p.z());
        portals.add(p);
        save();
        var player = e.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
            player.getInventory().setItem(hand, new ItemStack(Material.BUCKET));
        }
        Location mid = p.at(w, p.w() / 2, p.h() / 2).getLocation().add(0.5, 0.5, 0.5);
        w.playSound(mid, Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.6f);
        w.playSound(mid, Sound.ITEM_BUCKET_EMPTY, 1f, 1f);
        w.spawnParticle(Particle.END_ROD, mid, 60, p.alongX() ? p.w() / 2.0 : 0.2, p.h() / 2.0, p.alongX() ? 0.2 : p.w() / 2.0, 0.02);
    }

    // ---------------------------------------------------------------- closing it

    private void close(Portal p) {
        World w = Bukkit.getWorld(p.world());
        if (w != null) {
            for (int a = 0; a < p.w(); a++) for (int u = 0; u < p.h(); u++) {
                Block b = p.at(w, a, u);
                if (b.getType() == Material.END_GATEWAY) b.setType(Material.AIR, false);
            }
            Location mid = p.at(w, p.w() / 2, p.h() / 2).getLocation().add(0.5, 0.5, 0.5);
            w.playSound(mid, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.2f);
        }
        portals.remove(p);
        save();
    }

    private void frameBroken(Block b) {
        if (b.getType() != Material.GLOWSTONE) return;
        for (Portal p : new ArrayList<>(portals)) if (p.onFrame(b)) close(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { frameBroken(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { for (Block b : e.blockList()) frameBroken(b); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { for (Block b : e.blockList()) frameBroken(b); }
}
