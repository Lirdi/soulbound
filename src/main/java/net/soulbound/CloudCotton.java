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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cloud Cotton: Heaven's first crop. Pale stems that grow bolls of cloud. It only grows in Heaven (the End), on
 * grass, dirt, farmland, moss or end stone; carried anywhere else, the seeds won't take and planted ones stop.
 * Drawn over the tripwire states with attached=true (see {@link TripwireCrop}).
 *
 * Seeds come from the Peacekeeper. Bone meal pushes it a stage. A ripe plant drops 1-3 Cloud Cotton and 1-2 seeds,
 * and sheds the odd wisp of cloud when someone's near. Cloud Cotton is what turns a Potion of Growth into Shrinking.
 */
final class CloudCotton extends TripwireCrop {
    private static final TextColor NAME = TextColor.color(0xBFDDFF);
    private final NamespacedKey seedKey, cottonKey;

    CloudCotton(Soulbound plugin) {
        super(plugin, true, "cloud_cotton_crops");
        seedKey = new NamespacedKey(plugin, "cloud_cotton_seed");
        cottonKey = new NamespacedKey(plugin, "cloud_cotton");
    }

    // ---------------------------------------------------------------- items

    @Override
    ItemStack seed(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Cloud Cotton Seeds", NAME, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Only grows in Heaven, on grass, dirt or end stone.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Bone meal makes it grow.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "cloud_cotton_seeds"));
        m.getPersistentDataContainer().set(seedKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack cotton(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Cloud Cotton", NAME, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Lighter than it looks.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                Component.text("Brew into a Potion of Growth to shrink it.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "cloud_cotton"));
        m.getPersistentDataContainer().set(cottonKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    @Override
    boolean isSeed(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(seedKey, PersistentDataType.BYTE); }
    boolean isCotton(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(cottonKey, PersistentDataType.BYTE); }

    String adminGive(Player to, int seeds) {
        give(to, seed(seeds)); give(to, cotton(4));
        return "Gave " + to.getName() + " " + seeds + " Cloud Cotton Seeds and 4 Cloud Cotton.";
    }

    // ---------------------------------------------------------------- the crop

    @Override boolean soil(Material m) {
        return switch (m) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, MOSS_BLOCK, FARMLAND, END_STONE -> true;
            default -> false;
        };
    }
    @Override String wrongSoilMessage() { return "Cloud Cotton takes root in grass, dirt, farmland, moss or end stone."; }
    @Override boolean growsIn(org.bukkit.World w) { return w.getEnvironment() == org.bukkit.World.Environment.THE_END; }
    @Override String wrongWorldMessage() { return "Cloud Cotton only grows in Heaven."; }
    @Override boolean isFertiliser(ItemStack it) { return it != null && it.getType() == Material.BONE_MEAL; }

    @Override
    void fertilised(Block b) {
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(.5, .4, .5), 10, .3, .25, .3, 0);
        b.getWorld().playSound(b.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1f, 1f);
    }

    @Override
    void planted(Block b) { b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.8f, 1.4f); }

    @Override
    void dropRipe(Block b, Location at) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        b.getWorld().dropItemNaturally(at, cotton(1 + r.nextInt(3)));
        b.getWorld().dropItemNaturally(at, seed(1 + r.nextInt(2)));
        b.getWorld().playSound(at, Sound.BLOCK_WOOL_BREAK, 1f, 1.3f);
        b.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, .3, 0), 8, .25, .2, .25, 0.01);
    }

    /** a ripe plant sheds a wisp of cloud now and then, for whoever's near */
    @Override
    void ambient(Player p, Block ripe) {
        p.spawnParticle(Particle.CLOUD, ripe.getLocation().add(.5, .85, .5), 2, .25, .1, .25, 0.004);
    }
}
