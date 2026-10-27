package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * God's island is preserved. Within main-island-radius of the End's centre nobody (but admins with
 * soulbound.island.build) can break or place blocks, pour or scoop liquids, light fires or hang things, and nothing else
 * can change it either: explosions still hurt but break no blocks, endermen can't lift blocks, fire doesn't spread,
 * pistons and outside liquids can't push in. End Crystals are entities, so they can still be placed on the exit portal
 * and shot down as usual. The iron cages around the crystals are taken off so every crystal can be shot from the ground.
 */
final class IslandGuard implements Listener {
    private final Soulbound plugin;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    IslandGuard(Soulbound plugin) { this.plugin = plugin; }

    void start() { Bukkit.getScheduler().runTaskTimer(plugin, this::uncage, 100L, 100L); }

    private boolean on() { return plugin.getConfig().getBoolean("island.protect", true); }

    boolean inZone(Location l) {
        if (l == null || l.getWorld() == null || l.getWorld().getEnvironment() != World.Environment.THE_END) return false;
        double r = plugin.getConfig().getDouble("island.radius", 200);
        return l.getX() * l.getX() + l.getZ() * l.getZ() < r * r;
    }

    private boolean protectedFor(Player p, Location l) {
        return on() && inZone(l) && !p.hasPermission("soulbound.island.build");
    }

    private void warn(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        lastWarn.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text("This is God's house. Leave it as you found it.", NamedTextColor.GOLD));
    }

    // ---------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protectedFor(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protectedFor(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPour(PlayerBucketEmptyEvent e) {
        if (protectedFor(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onScoop(PlayerBucketFillEvent e) {
        if (protectedFor(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent e) {
        if (e.getPlayer() != null && protectedFor(e.getPlayer(), e.getEntity().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUnhang(HangingBreakByEntityEvent e) {
        if (!on() || !inZone(e.getEntity().getLocation())) return;
        if (e.getRemover() instanceof Player p && p.hasPermission("soulbound.island.build")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!on() || !inZone(e.getBlock().getLocation())) return;
        if (e.getPlayer() != null && e.getPlayer().hasPermission("soulbound.island.build")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoneMeal(BlockFertilizeEvent e) {
        if (!on() || !inZone(e.getBlock().getLocation())) return;
        if (e.getPlayer() != null && e.getPlayer().hasPermission("soulbound.island.build")) return;
        e.setCancelled(true);
    }

    // ---------------------------------------------------------------- everything else

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (on()) e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (on()) e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    /** Endermen lifting blocks, withers tunnelling, the dragon chewing through - none of it on the island. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (!on() || !inZone(e.getBlock().getLocation())) return;
        if (e.getEntity() instanceof Player p && p.hasPermission("soulbound.island.build")) return;
        if (e.getEntity() instanceof org.bukkit.entity.FallingBlock) return;   // let sand and gravel land
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (on() && inZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (on() && inZone(e.getBlock().getLocation()) && e.getNewState().getType() == Material.FIRE) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (on() && inZone(e.getToBlock().getLocation()) && !inZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) {
        if (on() && touches(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) {
        if (on() && touches(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    private boolean touches(Block piston, List<Block> moved, org.bukkit.block.BlockFace dir) {
        if (inZone(piston.getLocation())) return true;
        for (Block b : moved) if (inZone(b.getLocation()) || inZone(b.getRelative(dir).getLocation())) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        if (on() && inZone(e.getLocation()) && (e.getPlayer() == null || !e.getPlayer().hasPermission("soulbound.island.build"))) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- the crystal cages

    /** Every 5 seconds: take the iron cages off the crystal towers (a new God brings them back with fresh towers). */
    private void uncage() {
        if (!plugin.getConfig().getBoolean("island.remove-crystal-cages", true)) return;
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            for (int i = 0; i < 10; i++) {
                int sx = (int) Math.floor(42.0 * Math.cos(2.0 * (-Math.PI + (Math.PI / 10) * i)));
                int sz = (int) Math.floor(42.0 * Math.sin(2.0 * (-Math.PI + (Math.PI / 10) * i)));
                if (!w.isChunkLoaded(sx >> 4, sz >> 4)) continue;
                for (int x = sx - 3; x <= sx + 3; x++) for (int z = sz - 3; z <= sz + 3; z++) {
                    int top = w.getHighestBlockYAt(x, z);
                    for (int y = Math.max(w.getMinHeight(), top - 6); y <= top; y++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (b.getType() == Material.IRON_BARS) b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
