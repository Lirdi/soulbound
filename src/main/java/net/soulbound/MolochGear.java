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
import org.bukkit.configuration.file.FileConfiguration;
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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Moloch's spoils.
 *
 * Moloch's Horns: a cosmetic pair of bull horns worn on the head, for whoever hurt him most (the evil twin of God's Halo).
 *
 * Moloch's Regalia: a charred armour set with molten cracks, a little better than netherite (more toughness, more
 * knockback resistance, more durability, fireproof). Wear all four and Moloch's power swells in you: you grow to one
 * and a half times your size, and fire can't touch you.
 */
final class MolochGear implements Listener {
    enum Piece {
        HELMET(Material.NETHERITE_HELMET, EquipmentSlot.HEAD, EquipmentSlotGroup.HEAD, 3, "Helm of Moloch", 488),
        CHESTPLATE(Material.NETHERITE_CHESTPLATE, EquipmentSlot.CHEST, EquipmentSlotGroup.CHEST, 8, "Breastplate of Moloch", 710),
        LEGGINGS(Material.NETHERITE_LEGGINGS, EquipmentSlot.LEGS, EquipmentSlotGroup.LEGS, 6, "Greaves of Moloch", 666),
        BOOTS(Material.NETHERITE_BOOTS, EquipmentSlot.FEET, EquipmentSlotGroup.FEET, 3, "Hooves of Moloch", 577);
        final Material base; final EquipmentSlot slot; final EquipmentSlotGroup group; final int armor, durability; final String title;
        Piece(Material base, EquipmentSlot slot, EquipmentSlotGroup group, int armor, String title, int durability) {
            this.base = base; this.slot = slot; this.group = group; this.armor = armor; this.title = title; this.durability = durability;
        }
    }

    private final Soulbound plugin;
    private final Store store;
    private final NamespacedKey pieceKey, hornsKey, sizeKey;
    private final Set<UUID> empowered = new HashSet<>();
    private final Random rnd = new Random();
    private BukkitTask task, fireTask;
    private double setScale, dropChance;

    MolochGear(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        pieceKey = new NamespacedKey(plugin, "moloch_piece");
        hornsKey = new NamespacedKey(plugin, "moloch_horns");
        sizeKey = new NamespacedKey(plugin, "moloch_set_size");
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        setScale = c.getDouble("boss.regalia-set-scale", 1.5);
        dropChance = c.getDouble("boss.regalia-drop-chance", 0.15);
    }

    // ---------------------------------------------------------------- the items

    ItemStack piece(Piece p) {
        ItemStack it = new ItemStack(p.base);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(p.title, NamedTextColor.DARK_RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Forged in the furnace of his belly.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, true),
                Component.text("Moloch's Regalia (1 of 4)", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.text("Full set: you grow to " + fmt(setScale) + "x your size, and fire can't touch you.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "moloch_" + p.name().toLowerCase(Locale.ROOT)));
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(p.slot);
        eq.setModel(new NamespacedKey("soulbound", "moloch"));
        m.setEquippable(eq);
        // a little better than netherite: same armour, more toughness and knockback resistance
        String id = "moloch_" + p.name().toLowerCase(Locale.ROOT);
        m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, id + "_armor"), p.armor, AttributeModifier.Operation.ADD_NUMBER, p.group));
        m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(plugin, id + "_toughness"), 4, AttributeModifier.Operation.ADD_NUMBER, p.group));
        m.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(plugin, id + "_kb"), 0.15, AttributeModifier.Operation.ADD_NUMBER, p.group));
        if (m instanceof Damageable d) d.setMaxDamage(p.durability);
        m.getPersistentDataContainer().set(pieceKey, PersistentDataType.STRING, p.name());
        it.setItemMeta(m);
        return it;
    }

    ItemStack horns(String earnedBy) {
        ItemStack it = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Moloch's Horns", NamedTextColor.DARK_RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Torn from the idol's head.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, true),
                Component.text(earnedBy == null ? "Wear them on your head." : "Earned by " + earnedBy + ".", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "moloch_horns"));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(hornsKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private Piece pieceOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(pieceKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Piece.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ---------------------------------------------------------------- the spoils of the fight

    /** After Moloch falls: the top fighter takes his horns and a piece of the regalia; everyone else has a chance at a piece. */
    void reward(UUID top, Set<UUID> others) {
        if (top != null) {
            String name = Bukkit.getOfflinePlayer(top).getName();
            give(top, horns(name), "Moloch's Horns");
            give(top, piece(randomPiece()), null);
        }
        for (UUID id : others) if (!id.equals(top) && rnd.nextDouble() < dropChance) give(id, piece(randomPiece()), null);
    }

    private Piece randomPiece() { return Piece.values()[rnd.nextInt(Piece.values().length)]; }

    private void give(UUID id, ItemStack item, String announce) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {   // offline: keep it for them
            List<ItemStack> pending = new java.util.ArrayList<>();
            List<?> old = store.data.getList("moloch-gear.pending." + id);
            if (old != null) for (Object o : old) if (o instanceof ItemStack s) pending.add(s);
            pending.add(item);
            store.set("moloch-gear.pending." + id, pending);
            store.save();
            return;
        }
        for (ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), left);
        Component name = item.getItemMeta().displayName();
        p.sendMessage(Component.text("From Moloch's ruin you take: ", NamedTextColor.GOLD).append(name == null ? Component.text("a relic") : name));
        if (announce != null) Bukkit.broadcast(Component.text(p.getName() + " tears off " + announce + ".", NamedTextColor.DARK_RED, TextDecoration.BOLD));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String path = "moloch-gear.pending." + e.getPlayer().getUniqueId();
        List<?> list = store.data.getList(path);
        if (list == null) return;
        store.set(path, null); store.save();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Object o : list) if (o instanceof ItemStack s) for (ItemStack left : e.getPlayer().getInventory().addItem(s).values()) e.getPlayer().getWorld().dropItem(e.getPlayer().getLocation(), left);
            e.getPlayer().sendMessage(Component.text("Moloch's spoils were kept for you.", NamedTextColor.GOLD));
        }, 40L);
    }

    /** Admin: the whole set, one piece, or the horns. */
    String adminGive(Player to, String what) {
        switch (what.toLowerCase(Locale.ROOT)) {
            case "horns" -> to.getInventory().addItem(horns(null));
            case "set", "all" -> { for (Piece p : Piece.values()) to.getInventory().addItem(piece(p)); }
            default -> {
                try { to.getInventory().addItem(piece(Piece.valueOf(what.toUpperCase(Locale.ROOT)))); }
                catch (IllegalArgumentException e) { return "Give what? horns, set, helmet, chestplate, leggings or boots."; }
            }
        }
        return "Given.";
    }

    // ---------------------------------------------------------------- the set bonus

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSets, 40L, 20L);
        fireTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFurnaces, 40L, 3L);
    }

    /** The furnace in the breastplate really burns: little flames flicker out of it. */
    private void tickFurnaces() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pieceOf(p.getInventory().getChestplate()) != Piece.CHESTPLATE || p.isInvisible() || p.isDead()) continue;
            AttributeInstance sc = p.getAttribute(Attribute.SCALE);
            double s = sc != null ? sc.getValue() : 1;
            double yaw = Math.toRadians(p.getBodyYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double up = (p.isSneaking() ? 1.0 : 1.15) * s;
            org.bukkit.Location at = p.getLocation().add(fx * 0.2 * s, up, fz * 0.2 * s);
            p.getWorld().spawnParticle(Particle.SMALL_FLAME, at, 1, 0.05 * s, 0.07 * s, 0.05 * s, 0.006);
            if (Bukkit.getCurrentTick() % 9 == 0) p.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 0.15 * s, 0), 1, 0.03, 0.02, 0.03, 0.01);
        }
    }
    void stop() {
        if (task != null) task.cancel();
        if (fireTask != null) fireTask.cancel();
        for (UUID id : empowered) { Player p = Bukkit.getPlayer(id); if (p != null) shrink(p); }
        empowered.clear();
    }

    private void tickSets() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean full = pieceOf(p.getInventory().getHelmet()) == Piece.HELMET && pieceOf(p.getInventory().getChestplate()) == Piece.CHESTPLATE
                    && pieceOf(p.getInventory().getLeggings()) == Piece.LEGGINGS && pieceOf(p.getInventory().getBoots()) == Piece.BOOTS;
            boolean was = empowered.contains(p.getUniqueId());
            if (full) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, true, false, true));
                if (!was) grow(p);
                else if (Bukkit.getCurrentTick() % 60 < 20)
                    p.getWorld().spawnParticle(Particle.LAVA, p.getLocation().add(0, 1.2 * setScale, 0), 1, 0.3, 0.4, 0.3, 0);
            } else if (was || hasSizeBoost(p)) shrink(p);
        }
    }

    private void grow(Player p) {
        empowered.add(p.getUniqueId());
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null && a.getModifier(sizeKey) == null)
            a.addModifier(new AttributeModifier(sizeKey, setScale - 1, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
        p.getWorld().playSound(p.getLocation(), "moloch:furnace", 1.5f, 1.2f);
        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.05);
        p.sendActionBar(Component.text("Moloch's power swells within you.", NamedTextColor.DARK_RED, TextDecoration.BOLD));
    }

    private void shrink(Player p) {
        empowered.remove(p.getUniqueId());
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null && a.getModifier(sizeKey) != null) a.removeModifier(sizeKey);
    }

    private boolean hasSizeBoost(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        return a != null && a.getModifier(sizeKey) != null;
    }

    private static String fmt(double d) { return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d); }
}
