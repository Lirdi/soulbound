package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Vestments of the Ancient: God's own robes over white marble and gold, with a light in the breast that breathes
 * slowly brighter and dimmer. A little better than netherite: more armour, less toughness, a little knockback
 * resistance, and the sandals never take fall damage hard (Feather Falling IV built in). Wear all four and you're
 * lighter on your feet: a tenth smaller, with Haste I.
 */
final class GodGear implements Listener {
    enum Piece {
        HELMET(Material.NETHERITE_HELMET, EquipmentSlot.HEAD, EquipmentSlotGroup.HEAD, 4, "Hood of the Ancient", 500),
        CHESTPLATE(Material.NETHERITE_CHESTPLATE, EquipmentSlot.CHEST, EquipmentSlotGroup.CHEST, 9, "Robe of the Ancient", 720),
        LEGGINGS(Material.NETHERITE_LEGGINGS, EquipmentSlot.LEGS, EquipmentSlotGroup.LEGS, 7, "Skirts of the Ancient", 680),
        BOOTS(Material.NETHERITE_BOOTS, EquipmentSlot.FEET, EquipmentSlotGroup.FEET, 4, "Sandals of the Ancient", 590);
        final Material base; final EquipmentSlot slot; final EquipmentSlotGroup group; final int armor, durability; final String title;
        Piece(Material base, EquipmentSlot slot, EquipmentSlotGroup group, int armor, String title, int durability) {
            this.base = base; this.slot = slot; this.group = group; this.armor = armor; this.title = title; this.durability = durability;
        }
    }

    private final Soulbound plugin;
    private final Store store;
    private final NamespacedKey pieceKey, sizeKey;
    private final Set<UUID> blessed = new HashSet<>();
    private final Random rnd = new Random();
    private BukkitTask task, lightTask;
    private int frame;
    private static final int FRAMES = 6;

    GodGear(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        pieceKey = new NamespacedKey(plugin, "vestments_piece");
        sizeKey = new NamespacedKey(plugin, "vestments_set_size");
    }

    private double setScale() { return plugin.getConfig().getDouble("god.vestments-set-scale", 0.9); }

    ItemStack piece(Piece p) {
        ItemStack it = new ItemStack(p.base);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(p.title, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Still warm. It smells faintly of old books.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, true),
                Component.text("Vestments of the Ancient (1 of 4)", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("Full set: you're a tenth smaller, with Haste I.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        String id = "vestments_" + p.name().toLowerCase(Locale.ROOT);
        m.setItemModel(new NamespacedKey("soulbound", id));
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(p.slot);
        eq.setModel(new NamespacedKey("soulbound", "vestments"));
        m.setEquippable(eq);
        // a little better than netherite, the other way from Moloch's: more armour, less toughness
        m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, id + "_armor"), p.armor, AttributeModifier.Operation.ADD_NUMBER, p.group));
        m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(plugin, id + "_toughness"), 2, AttributeModifier.Operation.ADD_NUMBER, p.group));
        m.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(plugin, id + "_kb"), 0.1, AttributeModifier.Operation.ADD_NUMBER, p.group));
        if (p == Piece.BOOTS) m.addEnchant(Enchantment.FEATHER_FALLING, 4, true);
        if (m instanceof Damageable d) d.setMaxDamage(p.durability);
        m.getPersistentDataContainer().set(pieceKey, PersistentDataType.STRING, p.name());
        it.setItemMeta(m);
        return it;
    }

    private Piece pieceOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(pieceKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Piece.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ---------------------------------------------------------------- God's spoils

    /** When God falls: whoever hurt him most takes a piece; everyone else who fought has a chance at one. */
    void reward(UUID top, Set<UUID> fought) {
        double chance = plugin.getConfig().getDouble("god.vestments-drop-chance", 0.15);
        if (top != null) give(top, piece(randomPiece()));
        for (UUID id : fought) if (!id.equals(top) && rnd.nextDouble() < chance) give(id, piece(randomPiece()));
    }

    private Piece randomPiece() { return Piece.values()[rnd.nextInt(Piece.values().length)]; }

    private void give(UUID id, ItemStack item) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            List<ItemStack> pending = new ArrayList<>();
            List<?> old = store.data.getList("god-gear.pending." + id);
            if (old != null) for (Object o : old) if (o instanceof ItemStack s) pending.add(s);
            pending.add(item);
            store.set("god-gear.pending." + id, pending);
            store.save();
            return;
        }
        for (ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), left);
        Component name = item.getItemMeta().displayName();
        Bukkit.broadcast(Component.text(p.getName() + " takes ", NamedTextColor.YELLOW).append(name == null ? Component.text("a piece of the Vestments") : name)
                .append(Component.text(" from God's wardrobe.", NamedTextColor.YELLOW)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        List<?> pending = store.data.getList("god-gear.pending." + p.getUniqueId());
        if (pending == null || pending.isEmpty()) return;
        store.set("god-gear.pending." + p.getUniqueId(), null);
        store.save();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Object o : pending) if (o instanceof ItemStack s) for (ItemStack left : p.getInventory().addItem(s).values()) p.getWorld().dropItem(p.getLocation(), left);
            p.sendMessage(Component.text("While you were away, God's wardrobe was emptied in your favour.", NamedTextColor.GOLD));
        }, 40L);
    }

    /** Admin: the whole set or one piece. */
    String adminGive(Player to, String what) {
        switch (what.toLowerCase(Locale.ROOT)) {
            case "set", "all", "vestments" -> { for (Piece p : Piece.values()) to.getInventory().addItem(piece(p)); }
            default -> {
                try { to.getInventory().addItem(piece(Piece.valueOf(what.toUpperCase(Locale.ROOT)))); }
                catch (IllegalArgumentException e) { return "Give what? set, helmet, chestplate, leggings or boots."; }
            }
        }
        return "Given.";
    }

    // ---------------------------------------------------------------- the light in the breast, and the set bonus

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSets, 40L, 20L);
        lightTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickLight, 40L, 5L);
    }
    void stop() {
        if (task != null) task.cancel();
        if (lightTask != null) lightTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) unbless(p);
    }

    /** The robe's light breathes: the worn chest piece steps through six frames, slowly. */
    private void tickLight() {
        frame = (frame + 1) % FRAMES;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack chest = p.getInventory().getChestplate();
            if (pieceOf(chest) != Piece.CHESTPLATE) continue;
            ItemMeta m = chest.getItemMeta();
            EquippableComponent eq = m.getEquippable();
            NamespacedKey want = new NamespacedKey("soulbound", "vestments_f" + frame);
            if (want.equals(eq.getModel())) continue;
            eq.setModel(want);
            m.setEquippable(eq);
            chest.setItemMeta(m);
            p.getInventory().setChestplate(chest);
        }
    }

    private void tickSets() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean full = pieceOf(p.getInventory().getHelmet()) == Piece.HELMET && pieceOf(p.getInventory().getChestplate()) == Piece.CHESTPLATE
                    && pieceOf(p.getInventory().getLeggings()) == Piece.LEGGINGS && pieceOf(p.getInventory().getBoots()) == Piece.BOOTS;
            boolean was = blessed.contains(p.getUniqueId());
            if (full) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, 0, true, false, true));
                if (!was) bless(p);
                else if (Bukkit.getCurrentTick() % 80 < 20)
                    p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.0, 0), 1, 0.25, 0.4, 0.25, 0.005);
            } else if (was || hasSize(p)) unbless(p);
        }
    }

    private void bless(Player p) {
        blessed.add(p.getUniqueId());
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null && a.getModifier(sizeKey) == null)
            a.addModifier(new AttributeModifier(sizeKey, setScale() - 1, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
        p.getWorld().playSound(p.getLocation(), "minecraft:block.beacon.power_select", 1f, 1.6f);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.04);
        p.sendActionBar(Component.text("You feel lighter. Blessed, even.", NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    private void unbless(Player p) {
        blessed.remove(p.getUniqueId());
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null && a.getModifier(sizeKey) != null) a.removeModifier(sizeKey);
    }

    private boolean hasSize(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        return a != null && a.getModifier(sizeKey) != null;
    }
}
