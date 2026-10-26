package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

/**
 * Quantum Humanity: power armour built by people who went past good and evil and made their own creation. Fused at a
 * smithing table from a piece of Moloch's Regalia, the matching piece of the Vestments of the Ancient and a netherite
 * ingot. The best armour there is. Its bulk is real: whoever wears the chassis carries a 3D shell (chest plate, great
 * pauldrons, gorget and a backpack reactor) that turns with their body, its core cycling between Moloch's fire, God's
 * light and the quantum blue. The wearer doesn't see their own shell, so their view stays clear.
 * Full set: both powers fused - fire can't touch you, you never take fall damage, and Haste I.
 */
final class QuantumGear implements Listener {
    enum Piece {
        HELMET(Material.NETHERITE_HELMET, EquipmentSlot.HEAD, EquipmentSlotGroup.HEAD, 4, "Quantum Helm", 600),
        CHESTPLATE(Material.NETHERITE_CHESTPLATE, EquipmentSlot.CHEST, EquipmentSlotGroup.CHEST, 9, "Quantum Chassis", 860),
        LEGGINGS(Material.NETHERITE_LEGGINGS, EquipmentSlot.LEGS, EquipmentSlotGroup.LEGS, 7, "Quantum Greaves", 800),
        BOOTS(Material.NETHERITE_BOOTS, EquipmentSlot.FEET, EquipmentSlotGroup.FEET, 4, "Quantum Treads", 700);
        final Material base; final EquipmentSlot slot; final EquipmentSlotGroup group; final int armor, durability; final String title;
        Piece(Material base, EquipmentSlot slot, EquipmentSlotGroup group, int armor, String title, int durability) {
            this.base = base; this.slot = slot; this.group = group; this.armor = armor; this.title = title; this.durability = durability;
        }
    }
    private static final int CORE_FRAMES = 8;

    private final Soulbound plugin;
    private final NamespacedKey pieceKey, shellKey;
    private final Map<UUID, ItemDisplay> shells = new HashMap<>();
    private BukkitTask task, glowTask;
    private int glow;
    private long tick;

    QuantumGear(Soulbound plugin) {
        this.plugin = plugin;
        pieceKey = new NamespacedKey(plugin, "quantum_piece");
        shellKey = new NamespacedKey(plugin, "quantum_shell");
    }

    // ---------------------------------------------------------------- the pieces

    ItemStack piece(Piece p) {
        ItemStack it = new ItemStack(p.base);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(p.title, NamedTextColor.DARK_AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Past good and evil. Built, not given.", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, true),
                Component.text("Quantum Humanity (1 of 4)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Full set: fire can't touch you, no fall damage, Haste I.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        String id = "quantum_" + p.name().toLowerCase(Locale.ROOT);
        m.setItemModel(new NamespacedKey("soulbound", id));
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(p.slot);
        eq.setModel(new NamespacedKey("soulbound", "quantum"));
        m.setEquippable(eq);
        m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, id + "_armor"), p.armor, AttributeModifier.Operation.ADD_NUMBER, p.group));
        m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(plugin, id + "_toughness"), 4, AttributeModifier.Operation.ADD_NUMBER, p.group));
        m.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(plugin, id + "_kb"), 0.15, AttributeModifier.Operation.ADD_NUMBER, p.group));
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

    String adminGive(Player to, String what) {
        switch (what.toLowerCase(Locale.ROOT)) {
            case "set", "all" -> { for (Piece p : Piece.values()) to.getInventory().addItem(piece(p)); }
            default -> {
                try { to.getInventory().addItem(piece(Piece.valueOf(what.toUpperCase(Locale.ROOT)))); }
                catch (IllegalArgumentException e) { return "Give what? set, helmet, chestplate, leggings or boots."; }
            }
        }
        return "Given.";
    }

    // ---------------------------------------------------------------- fusion at the smithing table

    /** One recipe per piece so the smithing table accepts the inputs; the real check (Moloch + Vestments) is below. */
    void registerRecipes() {
        for (Piece p : Piece.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "quantum_fuse_" + p.name().toLowerCase(Locale.ROOT));
            Bukkit.removeRecipe(key);
            Bukkit.addRecipe(new SmithingTransformRecipe(key, piece(p), new RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT),
                    new RecipeChoice.MaterialChoice(p.base), new RecipeChoice.MaterialChoice(p.base), false));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSmith(PrepareSmithingEvent e) {
        SmithingInventory inv = e.getInventory();
        ItemStack tmpl = inv.getInputTemplate(), base = inv.getInputEquipment(), add = inv.getInputMineral();
        if (tmpl == null || tmpl.getType() != Material.NETHERITE_INGOT || base == null || add == null) return;
        if (base.getType() != add.getType()) return;
        String m1 = plugin.gear().pieceName(base), g1 = plugin.godGear().pieceName(base);
        String m2 = plugin.gear().pieceName(add), g2 = plugin.godGear().pieceName(add);
        String piece = (m1 != null && m1.equals(g2)) ? m1 : (g1 != null && g1.equals(m2)) ? g1 : null;
        boolean ours = Bukkit.getRecipe(new NamespacedKey(plugin, "quantum_fuse_" + base.getType().name().replace("NETHERITE_", "").toLowerCase(Locale.ROOT))) != null;
        if (piece == null) {
            if (ours && base.getType().name().startsWith("NETHERITE_")) e.setResult(null);   // two plain netherite pieces don't fuse
            return;
        }
        ItemStack out = piece(Piece.valueOf(piece));
        // the enchantments of both halves carry over (the stronger level wins)
        ItemMeta om = out.getItemMeta();
        for (ItemStack src : new ItemStack[]{base, add}) {
            if (!src.hasItemMeta()) continue;
            for (Entry<Enchantment, Integer> en : src.getItemMeta().getEnchants().entrySet())
                if (om.getEnchantLevel(en.getKey()) < en.getValue()) om.addEnchant(en.getKey(), en.getValue(), true);
        }
        out.setItemMeta(om);
        e.setResult(out);
    }

    // ---------------------------------------------------------------- the shell and the set bonus

    void start() {
        // tidy any shells left behind by a crash (they're never saved, but be safe)
        for (org.bukkit.World w : Bukkit.getWorlds())
            for (Entity en : w.getEntitiesByClass(ItemDisplay.class))
                if (en.getPersistentDataContainer().has(shellKey, PersistentDataType.BYTE)) en.remove();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickShells, 20L, 1L);
        glowTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGlow, 20L, 5L);
    }

    /** The reactor heart, the visor and the conduits pulse: swap the helm, chest and boots between six glow frames.
     *  This also moves old helms (once a 3D model) over to the new armour layer the first time they're worn. */
    private void tickGlow() {
        glow = (glow + 1) % 6;
        NamespacedKey want = new NamespacedKey("soulbound", "quantum_f" + glow);
        for (Player p : Bukkit.getOnlinePlayers()) {
            var inv = p.getInventory();
            ItemStack h = inv.getHelmet(), c = inv.getChestplate(), b = inv.getBoots();
            if (pieceOf(h) == Piece.HELMET) { ItemStack n = withModel(h, want); if (n != null) inv.setHelmet(n); }
            if (pieceOf(c) == Piece.CHESTPLATE) { ItemStack n = withModel(c, want); if (n != null) inv.setChestplate(n); }
            if (pieceOf(b) == Piece.BOOTS) { ItemStack n = withModel(b, want); if (n != null) inv.setBoots(n); }
        }
    }

    private static ItemStack withModel(ItemStack it, NamespacedKey model) {
        ItemMeta m = it.getItemMeta();
        EquippableComponent eq = m.getEquippable();
        if (model.equals(eq.getModel())) return null;
        eq.setModel(model);
        eq.setEquipSound(org.bukkit.Sound.INTENTIONALLY_EMPTY);   // swapped every few ticks: no clank each time
        m.setEquippable(eq);
        it.setItemMeta(m);
        return it;
    }

    void stop() {
        if (task != null) task.cancel();
        if (glowTask != null) glowTask.cancel();
        for (UUID id : List.copyOf(shells.keySet())) removeShell(id);
    }

    private boolean fullSet(Player p) {
        return pieceOf(p.getInventory().getHelmet()) == Piece.HELMET && pieceOf(p.getInventory().getChestplate()) == Piece.CHESTPLATE
                && pieceOf(p.getInventory().getLeggings()) == Piece.LEGGINGS && pieceOf(p.getInventory().getBoots()) == Piece.BOOTS;
    }

    private boolean shellShown(Player p) {
        if (!plugin.getConfig().getBoolean("quantum.shell", false)) return false;   // the sleek suit needs no shell; switch on for the bulky power-armour look
        if (pieceOf(p.getInventory().getChestplate()) != Piece.CHESTPLATE) return false;
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible() || !p.isValid()) return false;
        Pose pose = p.getPose();
        return pose == Pose.STANDING || pose == Pose.SNEAKING;
    }

    private void tickShells() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (tick % 20 == 0 && fullSet(p)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, 0, true, false, true));
            }
            ItemDisplay d = shells.get(p.getUniqueId());
            if (!shellShown(p)) { if (d != null) removeShell(p.getUniqueId()); continue; }
            if (d == null || !d.isValid() || !p.getPassengers().contains(d)) { if (d != null) d.remove(); d = spawnShell(p); }
            // turn with the body; sit lower and lean forward when sneaking; grow with the wearer
            float yaw = p.getBodyYaw() + (float) plugin.getConfig().getDouble("quantum.shell-yaw-offset", 180);
            if (Math.abs(d.getYaw() - yaw) > 0.5f) d.setRotation(yaw, 0f);
            AttributeInstance sc = p.getAttribute(Attribute.SCALE);
            float s = (float) (sc != null ? sc.getValue() : 1.0);
            boolean sneak = p.isSneaking();
            float ty = (float) (sneak ? plugin.getConfig().getDouble("quantum.shell-sneak-height", -0.57) : plugin.getConfig().getDouble("quantum.shell-height", -0.675)) * s;
            Transformation t = d.getTransformation();
            if (Math.abs(t.getTranslation().y - ty) > 0.001f || Math.abs(t.getScale().x - s) > 0.001f) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2);
                d.setTransformation(new Transformation(new Vector3f(0, ty, 0), new AxisAngle4f(sneak ? 0.45f : 0f, 1, 0, 0),
                        new Vector3f(s, s, s), new AxisAngle4f()));
            }
            if (tick % 3 == 0) d.setItemStack(shellItem((int) ((tick / 3) % CORE_FRAMES)));
        }
    }

    private ItemStack shellItem(int frame) {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("soulbound", "quantum_shell_f" + frame));
        it.setItemMeta(m);
        return it;
    }

    private ItemDisplay spawnShell(Player p) {
        ItemDisplay d = p.getWorld().spawn(p.getLocation(), ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(shellKey, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(shellItem(0));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTeleportDuration(2);
            e.setShadowRadius(0);
            e.setRotation(p.getBodyYaw() + (float) plugin.getConfig().getDouble("quantum.shell-yaw-offset", 180), 0f);
        });
        p.addPassenger(d);
        p.hideEntity(plugin, d);   // your own shell would fill your screen: only others see it
        shells.put(p.getUniqueId(), d);
        return d;
    }

    private void removeShell(UUID id) {
        ItemDisplay d = shells.remove(id);
        if (d == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.removePassenger(d);
        d.remove();
    }

    /** A rider would stop a teleport (portals, /tp, Moloch's gates): take the shell off first; it comes back next tick. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) { removeShell(e.getPlayer().getUniqueId()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { removeShell(e.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { removeShell(e.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { removeShell(e.getPlayer().getUniqueId()); }

    /** Full set: the reactor catches you. No fall damage. */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && fullSet(p)) {
            e.setCancelled(true);
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 0.1, 0), 20, 0.4, 0.05, 0.4, 0.1);
        }
    }
}
