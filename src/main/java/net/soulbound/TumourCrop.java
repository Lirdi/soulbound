package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The tumour crop: a lumpy 3D mass of flesh that grows out of soul soil or soul sand, like nether wart.
 * Drawn over the tripwire states with attached=false (see {@link TripwireCrop}).
 *
 * A ripe mass drops 2-4 Tumours and 1-2 Tumour Seeds, an unripe one its seed. Rotten flesh on a crop pushes it a
 * stage. Left ripe, a mass sometimes spreads to bare soul soil next to it, and you can hear it beating nearby.
 */
final class TumourCrop extends TripwireCrop {
    private final NamespacedKey seedKey, tumourKey;

    TumourCrop(Soulbound plugin) {
        super(plugin, false, "tumour_crops");
        seedKey = new NamespacedKey(plugin, "tumour_seed");
        tumourKey = new NamespacedKey(plugin, "tumour");
    }

    // ---------------------------------------------------------------- items

    @Override
    ItemStack seed(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Tumour Seed", TextColor.color(0xD9707E), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Plant on soul soil or soul sand.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Rotten flesh makes it grow.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "tumour_seed"));
        m.getPersistentDataContainer().set(seedKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack tumour(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Tumour", TextColor.color(0xD9707E), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Still warm.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)));
        m.setItemModel(new NamespacedKey("soulbound", "tumour"));
        m.getPersistentDataContainer().set(tumourKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    @Override
    boolean isSeed(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(seedKey, PersistentDataType.BYTE); }
    boolean isTumour(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(tumourKey, PersistentDataType.BYTE); }

    String adminGive(Player to, int seeds) {
        give(to, seed(seeds)); give(to, tumour(4));
        return "Gave " + to.getName() + " " + seeds + " Tumour Seeds and 4 Tumours.";
    }

    // ---------------------------------------------------------------- the crop

    @Override boolean soil(Material m) { return m == Material.SOUL_SOIL || m == Material.SOUL_SAND; }
    @Override String wrongSoilMessage() { return "Tumours only take root in soul soil or soul sand."; }
    @Override boolean isFertiliser(ItemStack it) { return it != null && it.getType() == Material.ROTTEN_FLESH; }

    @Override
    void fertilised(Block b) {
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(.5, .3, .5), 14, .3, .2, .3, Material.NETHER_WART_BLOCK.createBlockData());
        b.getWorld().playSound(b.getLocation(), Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.6f);
    }

    @Override
    void planted(Block b) { b.getWorld().playSound(b.getLocation(), Sound.BLOCK_HONEY_BLOCK_PLACE, 0.8f, 0.6f); }

    @Override
    void dropRipe(Block b, Location at) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        b.getWorld().dropItemNaturally(at, tumour(2 + r.nextInt(3)));
        b.getWorld().dropItemNaturally(at, seed(1 + r.nextInt(2)));
        b.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 1f, 0.5f);
        b.getWorld().spawnParticle(Particle.BLOCK, at, 20, .3, .3, .3, Material.NETHER_WART_BLOCK.createBlockData());
    }

    /** a quiet heartbeat from a ripe mass (the pack makes them throb; this is the sound of it) */
    @Override
    void ambient(Player p, Block ripe) { p.playSound(ripe.getLocation().add(.5, .5, .5), Sound.ENTITY_WARDEN_HEARTBEAT, 0.35f, 1.3f); }

    @Override double spreadChance() { return 1.0 / 900; }   // per pass while ripe: about once an hour and a quarter

    /** a ripe mass reaches into bare soul soil beside it */
    @Override
    void spread(Block b) {
        BlockFace f = new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}[ThreadLocalRandom.current().nextInt(4)];
        Block ground = b.getRelative(BlockFace.DOWN).getRelative(f), above = ground.getRelative(BlockFace.UP);
        if (soil(ground.getType()) && above.getType().isAir()) {
            set(above, 0);
            above.getWorld().spawnParticle(Particle.BLOCK, above.getLocation().add(.5, .2, .5), 10, .25, .1, .25, Material.NETHER_WART_BLOCK.createBlockData());
        }
    }
}
