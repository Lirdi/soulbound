package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The Thief's Key: an old brass skeleton key, dropped by the Bastard Thief when you kill him. Right-click an iron door
 * or iron trapdoor with it to open (or shut) it without redstone. A few uses, then it snaps.
 */
final class ThiefKey implements Listener {
    private final Soulbound plugin;
    private final NamespacedKey key;

    ThiefKey(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "thief_key"); }

    private int uses() { return Math.max(1, plugin.getConfig().getInt("thief-key.uses", 3)); }

    ItemStack create() {
        ItemStack it = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Thief's Key", TextColor.color(0xD9A441)).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Prised off a Bastard Thief.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                Component.text("Opens iron doors and trapdoors.", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "thief_key"));
        m.setMaxStackSize(1);
        if (m instanceof Damageable d) d.setMaxDamage(uses());
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** The Bastard Thief (the Sacrifice datapack's thief) always drops one when a player kills him. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onThiefDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains("sacrifice_thief") || e.getEntity().getKiller() == null) return;
        if (Math.random() < plugin.getConfig().getDouble("thief-key.drop-chance", 1.0)) e.getDrops().add(create());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !is(e.getItem())) return;
        Block b = e.getClickedBlock();
        boolean door = b.getType() == Material.IRON_DOOR, trap = b.getType() == Material.IRON_TRAPDOOR;
        if (!door && !trap) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;
        Player p = e.getPlayer();
        boolean open = !((Openable) b.getBlockData()).isOpen();
        setOpen(b, open);
        if (door) {   // both halves of the door
            Bisected.Half half = ((Bisected) b.getBlockData()).getHalf();
            Block other = b.getRelative(half == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN);
            if (other.getType() == Material.IRON_DOOR) setOpen(other, open);
        }
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_CHEST_LOCKED, 0.7f, 1.6f);
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), open ? (door ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_TRAPDOOR_OPEN)
                : (door ? Sound.BLOCK_IRON_DOOR_CLOSE : Sound.BLOCK_IRON_TRAPDOOR_CLOSE), 1f, 1f);
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack it = e.getItem();
        ItemMeta m = it.getItemMeta();
        if (m instanceof Damageable d) {
            int dmg = d.getDamage() + 1;
            if (dmg >= d.getMaxDamage()) {
                it.setAmount(0);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1.2f);
                p.sendActionBar(Component.text("The Thief's Key snaps in the lock.", NamedTextColor.GRAY));
                return;
            }
            d.setDamage(dmg);
            it.setItemMeta(m);
        }
    }

    private static void setOpen(Block b, boolean open) {
        Openable o = (Openable) b.getBlockData();
        o.setOpen(open);
        b.setBlockData(o, false);
    }
}
