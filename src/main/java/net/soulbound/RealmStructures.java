package net.soulbound;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureSpawnEvent;
import org.bukkit.util.BoundingBox;

import java.util.List;

/**
 * Keeps the realm's scattered structures (Blood Altars, Golden Calves, fortresses...) away from the temple: none of them
 * generates within realm-structures.min-distance blocks of the centre. Only affects chunks generated from now on.
 */
final class RealmStructures implements Listener {
    private final Soulbound plugin;

    RealmStructures(Soulbound plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onStructure(AsyncStructureSpawnEvent e) {
        String world = plugin.getConfig().getString("moloch.world", "sacrifice:sacrifice");
        if (!e.getWorld().getKey().toString().equals(world)) return;
        NamespacedKey key = e.getStructure().getKey();
        List<String> kept = plugin.getConfig().getStringList("realm-structures.keep-away");
        if (key == null || !kept.contains(key.toString())) return;
        double min = plugin.getConfig().getDouble("realm-structures.min-distance", 500);
        BoundingBox b = e.getBoundingBox();
        double x = b.getCenterX(), z = b.getCenterZ();
        if (x * x + z * z < min * min) e.setCancelled(true);
    }
}
