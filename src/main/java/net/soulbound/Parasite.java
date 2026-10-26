package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Black Filament: a parasite worn on the chest. A glossy black worm latched onto your sternum, its tail
 * running up under your collar and down your spine, always writhing. It keeps you alive (Regeneration) and
 * eats everything you eat (Hunger). It won't come off (Curse of Binding) until you starve it out.
 */
final class Parasite implements Listener {
    private static final int FRAMES = 8;

    private final Soulbound plugin;
    private final NamespacedKey key;
    private final Set<UUID> hosts = new HashSet<>();
    private BukkitTask animTask, task;
    private int frame;

    private int regen, hunger;
    private boolean binding, starveOff;

    Parasite(Soulbound plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "parasite");
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        regen = Math.max(0, c.getInt("parasite.regeneration", 2));
        hunger = Math.max(0, c.getInt("parasite.hunger", 2));
        binding = c.getBoolean("parasite.binding", true);
        starveOff = c.getBoolean("parasite.lets-go-when-starving", true);
    }

    void start() {
        animTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 20L, 3L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    void stop() {
        if (animTask != null) animTask.cancel();
        if (task != null) task.cancel();
        for (UUID id : hosts) { Player p = Bukkit.getPlayer(id); if (p != null) clearEffects(p); }
        hosts.clear();
    }

    ItemStack create() {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("The Black Filament", NamedTextColor.DARK_GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("It feeds on you. You feed on it.", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, true),
                Component.text("Parasite · worn on the chest", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("Regeneration " + roman(regen) + ", Hunger " + roman(hunger), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(binding ? "It won't let go until you starve it out." : "Pull it off whenever you dare.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "parasite"));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.CHEST);
        eq.setModel(new NamespacedKey("soulbound", "parasite_f0"));
        eq.setEquipSound(Sound.INTENTIONALLY_EMPTY);   // it writhes every few ticks: no armour clank each time
        eq.setDispensable(true);
        eq.setSwappable(true);
        m.setEquippable(eq);
        if (binding) m.addEnchant(Enchantment.BINDING_CURSE, 1, true);
        m.setEnchantmentGlintOverride(false);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    String adminGive(Player t) {
        var left = t.getInventory().addItem(create());
        left.values().forEach(i -> t.getWorld().dropItem(t.getLocation(), i));
        return "Gave " + t.getName() + " the Black Filament.";
    }

    // ---------------------------------------------------------------- it writhes

    private void animate() {
        frame = (frame + 1) % FRAMES;
        NamespacedKey want = new NamespacedKey("soulbound", "parasite_f" + frame);
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack chest = p.getInventory().getChestplate();
            if (!is(chest)) continue;
            ItemMeta m = chest.getItemMeta();
            EquippableComponent eq = m.getEquippable();
            if (want.equals(eq.getModel())) continue;
            eq.setModel(want);
            m.setEquippable(eq);
            chest.setItemMeta(m);
            p.getInventory().setChestplate(chest);
        }
    }

    // ---------------------------------------------------------------- it feeds

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean wearing = is(p.getInventory().getChestplate()) && !p.isDead();
            if (!wearing) {
                if (hosts.remove(id)) clearEffects(p);
                continue;
            }
            if (hosts.add(id)) latch(p);
            if (starveOff && p.getFoodLevel() <= 0 && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
                letGo(p);
                continue;
            }
            refresh(p, PotionEffectType.REGENERATION, regen);
            refresh(p, PotionEffectType.HUNGER, hunger);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location chest = p.getLocation().add(0, 1.15, 0).add(p.getLocation().getDirection().setY(0).normalize().multiply(0.3));
            if (r.nextInt(5) == 0) p.getWorld().spawnParticle(Particle.SQUID_INK, chest, 1, 0.12, 0.15, 0.12, 0.0);
            if (r.nextInt(24) == 0) p.getWorld().playSound(p.getLocation(), "minecraft:entity.slime.squish_small", 0.35f, 0.55f + r.nextFloat() * 0.2f);
        }
    }

    private static void refresh(Player p, PotionEffectType type, int level) {
        if (level <= 0) return;
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && (cur.getAmplifier() > level - 1 || cur.getDuration() > 60 || cur.isInfinite())) return;
        p.addPotionEffect(new PotionEffect(type, 200, level - 1, true, false, true));
    }

    /** Take off only the effects it gave (short, ambient ones), not a potion the player drank. */
    private void clearEffects(Player p) {
        for (PotionEffectType type : new PotionEffectType[]{PotionEffectType.REGENERATION, PotionEffectType.HUNGER}) {
            PotionEffect cur = p.getPotionEffect(type);
            if (cur != null && cur.isAmbient() && !cur.isInfinite() && cur.getDuration() <= 200) p.removePotionEffect(type);
        }
    }

    private void latch(Player p) {
        p.getWorld().playSound(p.getLocation(), "minecraft:entity.slime.squish", 0.9f, 0.5f);
        p.getWorld().playSound(p.getLocation(), "minecraft:block.honey_block.slide", 0.9f, 0.6f);
        p.getWorld().spawnParticle(Particle.SQUID_INK, p.getLocation().add(0, 1.2, 0), 8, 0.2, 0.25, 0.2, 0.02);
        p.sendActionBar(Component.text("Something cold slides under your skin.", NamedTextColor.DARK_RED));
    }

    /** Nothing left to eat: it drops off, into your hands (or onto the ground). */
    private void letGo(Player p) {
        ItemStack it = p.getInventory().getChestplate();
        p.getInventory().setChestplate(null);
        hosts.remove(p.getUniqueId());
        clearEffects(p);
        if (it != null) {
            var left = p.getInventory().addItem(it);
            left.values().forEach(i -> p.getWorld().dropItem(p.getLocation().add(0, 1, 0), i).setVelocity(new Vector(0, 0.2, 0)));
        }
        p.getWorld().playSound(p.getLocation(), "minecraft:entity.slime.squish", 1f, 0.4f);
        p.getWorld().spawnParticle(Particle.SQUID_INK, p.getLocation().add(0, 1.1, 0), 14, 0.25, 0.3, 0.25, 0.03);
        p.sendMessage(Component.text("Starved of food, the Black Filament lets go of you.", NamedTextColor.DARK_RED));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { hosts.remove(e.getPlayer().getUniqueId()); }

    /** It's an echo shard underneath: don't let it be crafted into a recovery compass. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (is(it)) { e.getInventory().setResult(null); return; }
    }

    private static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }
}
