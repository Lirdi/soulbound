package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zoglin;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.ZombieHorse;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Angel Engine: the Heart of Darkness, mended in gold.
 *
 * Eaten by someone inhuman, it makes them human again. Eaten by anyone else, it Graces them for good: undead take more
 * damage from them, the Archangel ghasts of Heaven leave them be (God's own host still fights them while He does), and
 * when they fall low a Second Wind of Regeneration lifts them (once a minute). Grace and the Heart of Darkness can't live
 * in the same body: eating the Heart of Darkness puts the light out. In Heaven (the End) the Graced carry three more
 * hearts.
 */
final class Grace implements Listener {
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(255, 214, 96), 1.0f);
    private final Soulbound plugin;
    private final NamespacedKey key, heartKey, heavenHeartsKey;
    private final Map<UUID, Long> lastWind = new HashMap<>();
    private Tag<EntityType> undeadTag;
    private boolean tagLooked;

    Grace(Soulbound plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "graced");
        this.heartKey = new NamespacedKey(plugin, "heart_of_light");
        this.heavenHeartsKey = new NamespacedKey(plugin, "grace_heaven_hearts");
    }

    // ---------------------------------------------------------------- three more hearts in Heaven

    /** the Graced carry extra hearts while they're in Heaven (the End); everywhere else, and for everyone else, none */
    void syncHearts(Player p) {
        var hp = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (hp == null) return;
        double extra = plugin.getConfig().getDouble("grace.heaven-extra-health", 6);
        boolean want = extra > 0 && is(p) && p.getWorld().getEnvironment() == org.bukkit.World.Environment.THE_END;
        var cur = hp.getModifier(heavenHeartsKey);
        if (want && cur != null && cur.getAmount() == extra) return;
        if (cur != null) hp.removeModifier(heavenHeartsKey);
        if (want) hp.addModifier(new org.bukkit.attribute.AttributeModifier(heavenHeartsKey, extra, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) { syncHearts(e.getPlayer()); }

    @EventHandler
    public void onJoinHearts(org.bukkit.event.player.PlayerJoinEvent e) { syncHearts(e.getPlayer()); }

    @EventHandler
    public void onRespawnHearts(org.bukkit.event.player.PlayerRespawnEvent e) { Bukkit.getScheduler().runTask(plugin, () -> syncHearts(e.getPlayer())); }

    // ---------------------------------------------------------------- the item and the state

    ItemStack heart() {
        ItemStack it = new ItemStack(Material.APPLE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Angel Engine", NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Broken once. Mended in gold.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, true),
                Component.text("Eat it, and remember who you are.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Undoes the Heart of Darkness.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "heart_of_light"));
        m.setMaxStackSize(1);
        FoodComponent food = m.getFood();
        food.setCanAlwaysEat(true);
        food.setNutrition(1);
        food.setSaturation(0f);
        m.setFood(food);
        m.getPersistentDataContainer().set(heartKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    /** Hearts made before the rename are renamed (and re-lored) when they turn up in an inventory. */
    private void rename(ItemStack it) {
        if (!isHeart(it)) return;
        ItemStack fresh = heart();
        var m = it.getItemMeta(); var f = fresh.getItemMeta();
        if (java.util.Objects.equals(m.displayName(), f.displayName())) return;
        m.displayName(f.displayName());
        m.lore(f.lore());
        it.setItemMeta(m);
    }

    @EventHandler
    public void onJoinRename(org.bukkit.event.player.PlayerJoinEvent e) { for (ItemStack it : e.getPlayer().getInventory().getContents()) rename(it); }

    @EventHandler(ignoreCancelled = true)
    public void onHeldRename(org.bukkit.event.player.PlayerItemHeldEvent e) { rename(e.getPlayer().getInventory().getItem(e.getNewSlot())); }

    boolean isHeart(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(heartKey, PersistentDataType.BYTE);
    }

    boolean is(Player p) { return p.getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    void set(Player p, boolean graced) {
        if (graced) p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        else p.getPersistentDataContainer().remove(key);
        syncHearts(p);
    }

    /** Heaven's own ghasts leave a Graced player alone - except while God Himself is fighting. */
    boolean spared(Player p) { return is(p) && (plugin.god() == null || !plugin.god().fighting()); }

    // ---------------------------------------------------------------- eating

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (!isHeart(e.getItem())) return;
        Player p = e.getPlayer();
        boolean inhuman = plugin.inhuman() != null && plugin.inhuman().is(p);
        if (!inhuman && is(p)) {
            e.setCancelled(true);
            p.sendActionBar(Component.text("The light is already in you.", NamedTextColor.GOLD));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> { if (inhuman) cure(p); else bless(p); });
    }

    private void glow(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, false, true));
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 50, 0.4, 0.9, 0.4, 0.06);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0, GOLD);
        p.playSound(p.getLocation(), "minecraft:block.beacon.activate", 1f, 1.4f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.playSound(p.getLocation(), "minecraft:block.amethyst_block.resonate", 1f, 1.6f), 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.playSound(p.getLocation(), "minecraft:block.amethyst_block.resonate", 1f, 2.0f), 22L);
    }

    private void voice(Player p, String line, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Component.text("A quiet voice: ", NamedTextColor.GOLD, TextDecoration.ITALIC)
                    .append(Component.text(line, NamedTextColor.WHITE, TextDecoration.ITALIC)));
        }, delay);
    }

    private void cure(Player p) {
        if (!p.isOnline()) return;
        plugin.inhuman().set(p, false);
        glow(p);
        voice(p, "There you are.", 10L);
        voice(p, "Welcome back.", 60L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(Component.text("You are human again. Heaven's gates are open to you.", NamedTextColor.GOLD));
        }, 70L);
    }

    private void bless(Player p) {
        if (!p.isOnline()) return;
        set(p, true);
        glow(p);
        voice(p, "There you are.", 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(Component.text("You are Graced. The dead fear your hand, Heaven's Archangels know you, and when you fall, the light lifts you.", NamedTextColor.GOLD));
        }, 50L);
    }

    // ---------------------------------------------------------------- Holy Light: the dead fear your hand

    private boolean undead(Entity e) {
        if (!tagLooked) {
            tagLooked = true;
            try { undeadTag = Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, NamespacedKey.minecraft("undead"), EntityType.class); }
            catch (Exception ignored) { undeadTag = null; }
        }
        if (undeadTag != null) return undeadTag.isTagged(e.getType());
        return e instanceof Zombie || e instanceof AbstractSkeleton || e instanceof Phantom || e instanceof Wither
                || e instanceof Zoglin || e instanceof ZombieHorse || e instanceof SkeletonHorse;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStrike(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
        if (!(d instanceof Player p) || !is(p) || !undead(e.getEntity())) return;
        e.setDamage(e.getDamage() * plugin.getConfig().getDouble("grace.undead-damage-multiplier", 1.2));
    }

    // ---------------------------------------------------------------- Heaven knows you

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !spared(p)) return;
        if (plugin.archangels() != null && plugin.archangels().isArchangel(e.getEntity())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- Second Wind

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !is(p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            if (p.getHealth() > plugin.getConfig().getDouble("grace.second-wind-below", 6)) return;
            long now = System.currentTimeMillis();
            Long last = lastWind.get(p.getUniqueId());
            if (last != null && now - last < plugin.getConfig().getLong("grace.second-wind-cooldown-seconds", 60) * 1000) return;
            lastWind.put(p.getUniqueId(), now);
            int secs = plugin.getConfig().getInt("grace.second-wind-seconds", 5);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, secs * 20, 0, false, true, true));
            p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 24, 0.35, 0.7, 0.35, 0, GOLD);
            p.playSound(p.getLocation(), "minecraft:block.amethyst_block.resonate", 0.8f, 1.8f);
            p.sendActionBar(Component.text("Second Wind. Get up.", NamedTextColor.GOLD));
        });
    }
}
