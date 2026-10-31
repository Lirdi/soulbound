package net.soulbound;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureSpawnEvent;
import org.bukkit.util.BoundingBox;

import java.util.List;

/**
 * Keeps the realm's scattered structures (Blood Altars, Golden Calves, fortresses...) away from the temple: none of them
 * generates within realm-structures.min-distance blocks of the centre. And no structure at all (bar the temple's own arena
 * and shrine) comes within realm-structures.clear-radius of it. Only affects chunks generated from now on.
 */
final class RealmStructures implements Listener {
    private final Soulbound plugin;

    RealmStructures(Soulbound plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onStructure(AsyncStructureSpawnEvent e) {
        String world = plugin.getConfig().getString("moloch.world", "sacrifice:sacrifice");
        if (!e.getWorld().getKey().toString().equals(world)) return;
        NamespacedKey key = e.getStructure().getKey();
        if (key == null) return;
        // nothing at all generates within clear-radius of the temple, except the temple's own pieces
        double clear = plugin.getConfig().getDouble("realm-structures.clear-radius", 400);
        List<String> allowed = plugin.getConfig().isList("realm-structures.always-allowed")
                ? plugin.getConfig().getStringList("realm-structures.always-allowed") : List.of("sacrifice:sanctum_arena", "sacrifice:sanctum_shrine");
        if (clear > 0 && !allowed.contains(key.toString())) {
            BoundingBox bb = e.getBoundingBox();   // the structure's nearest point to the centre
            double nx = Math.max(bb.getMinX(), Math.min(0, bb.getMaxX())), nz = Math.max(bb.getMinZ(), Math.min(0, bb.getMaxZ()));
            if (nx * nx + nz * nz < clear * clear) { e.setCancelled(true); return; }
        }
        List<String> kept = plugin.getConfig().getStringList("realm-structures.keep-away");
        if (!kept.contains(key.toString())) return;
        double min = plugin.getConfig().getDouble("realm-structures.min-distance", 500);
        BoundingBox b = e.getBoundingBox();
        double x = b.getCenterX(), z = b.getCenterZ();
        if (x * x + z * z < min * min) e.setCancelled(true);
    }
}
