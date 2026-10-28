package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.FallingBlock;
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
 * The four roads of Moloch's realm (the Sacred Way and its sisters, running out along both axes from the temple) are
 * holy ground from bedrock to sky: nobody without soulbound.realm.build can break, place, pour or burn anything on them,
 * above them or under them, and explosions, endermen, pistons and outside liquids can't change them either.
 * The battle hall in the middle is left alone.
 */
final class RoadGuard implements Listener {
    private final Soulbound plugin;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    RoadGuard(Soulbound plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("roads.protect", true); }

    boolean onRoad(Location l) {
        if (l == null || !plugin.zones().inRealm(l)) return false;
        double ax = Math.abs(l.getX() - 0.5), az = Math.abs(l.getZ() - 0.5);
        double across = Math.min(ax, az), along = Math.max(ax, az);
        var c = plugin.getConfig();
        return across <= c.getDouble("roads.half-width", 12) && along >= c.getDouble("roads.from", 35) && along <= c.getDouble("roads.length", 1200);
    }

    private boolean guarded(Location l) { return on() && onRoad(l); }

    private boolean blocked(Player p, Location l) { return guarded(l) && !p.hasPermission("soulbound.realm.build"); }

    private void warn(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        lastWarn.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text("This road belongs to Moloch. Leave it be.", NamedTextColor.DARK_RED));
    }

    // ---------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (blocked(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (blocked(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPour(PlayerBucketEmptyEvent e) { if (blocked(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onScoop(PlayerBucketFillEvent e) { if (blocked(e.getPlayer(), e.getBlock().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent e) {
        if (e.getPlayer() != null && blocked(e.getPlayer(), e.getEntity().getLocation())) { e.setCancelled(true); warn(e.getPlayer()); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUnhang(HangingBreakByEntityEvent e) {
        if (!guarded(e.getEntity().getLocation())) return;
        if (e.getRemover() instanceof Player p && p.hasPermission("soulbound.realm.build")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!guarded(e.getBlock().getLocation())) return;
        if (e.getPlayer() != null && e.getPlayer().hasPermission("soulbound.realm.build")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoneMeal(BlockFertilizeEvent e) {
        if (!guarded(e.getBlock().getLocation())) return;
        if (e.getPlayer() != null && e.getPlayer().hasPermission("soulbound.realm.build")) return;
        e.setCancelled(true);
    }

    // ---------------------------------------------------------------- everything else

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { if (on()) e.blockList().removeIf(b -> onRoad(b.getLocation())); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (on()) e.blockList().removeIf(b -> onRoad(b.getLocation())); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (!guarded(e.getBlock().getLocation())) return;
        if (e.getEntity() instanceof Player p && p.hasPermission("soulbound.realm.build")) return;
        if (e.getEntity() instanceof FallingBlock) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (guarded(e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (guarded(e.getBlock().getLocation()) && e.getNewState().getType() == Material.FIRE) e.setCancelled(true);
    }

    /** Liquids poured beside the road can't run onto it (the realm's own lava and water still flow). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (on() && onRoad(e.getToBlock().getLocation()) && !onRoad(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) { if (on() && touches(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) { if (on() && touches(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true); }

    private boolean touches(Block piston, List<Block> moved, BlockFace dir) {
        if (onRoad(piston.getLocation())) return true;
        for (Block b : moved) if (onRoad(b.getLocation()) || onRoad(b.getRelative(dir).getLocation())) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        if (guarded(e.getLocation()) && (e.getPlayer() == null || !e.getPlayer().hasPermission("soulbound.realm.build"))) e.setCancelled(true);
    }
}
