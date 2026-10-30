package net.soulbound;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Spiked Cultist Axe: a rusted, bloody crescent axe of Moloch's cult.
 * An iron axe underneath (iron durability, repaired with iron ingots), 11 attack damage at axe speed (1.0), takes normal
 * enchantments. Heavy: -15% movement speed while held in the main hand.
 * - Beheading: a melee killing blow with it gives the killer the victim's head (player heads, and villager heads), each
 *   carrying trophy lore: who took it, with what, and when. Player beheadings get their own death message.
 * - Executioner: +25% damage to anything below 30% health.
 * - Headhunter: beheading a player makes the killer glow for 2 minutes; milk won't wash it off.
 * - The axe counts the heads it has taken; at 10, 25 and 50 its texture gets bloodier.
 */
final class CultistAxe implements Listener {
    static final String NAME = "Spiked Cultist Axe";
    static final double DAMAGE = 10;        // +1 base = 11 attack damage
    static final double SPEED = -3.0;       // 4 - 3 = 1.0 swings a second, a netherite axe's
    static final double SLOW = -0.15;       // the weight of it: Slowness I's worth, only while held
    static final double EXECUTE_BELOW = 0.30, EXECUTE_BONUS = 1.25;
    static final int GLOW_TICKS = 20 * 120;
    static final int[] STAGES = {10, 25, 50};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final Soulbound plugin;
    private final NamespacedKey key, headsKey, villagerHeadKey;
    private final Map<UUID, Long> headhunters = new HashMap<>();
    private volatile PlayerProfile villagerProfile;

    CultistAxe(Soulbound plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "cultist_axe");
        headsKey = new NamespacedKey(plugin, "cultist_axe_heads");
        villagerHeadKey = new NamespacedKey(plugin, "beheaded_villager");
        // Mojang's own MHF_Villager head; looked up once, off the main thread. Until it resolves, a name-only profile
        // is used, which the client fills in by itself.
        villagerProfile = Bukkit.createProfile(null, "MHF_Villager");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerProfile p = Bukkit.createProfile(null, "MHF_Villager");
                if (p.complete(true)) villagerProfile = p;
            } catch (Exception ex) {
                plugin.getLogger().warning("Cultist Axe: couldn't look up the MHF_Villager head skin: " + ex.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- the item

    ItemStack item() {
        ItemStack it = new ItemStack(Material.IRON_AXE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(NAME, TextColor.color(0xB0202A), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        // setting any modifier replaces the iron axe's defaults, so damage and speed are given in full
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(plugin, "cultist_axe_damage"), DAMAGE, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey(plugin, "cultist_axe_speed"), SPEED, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.MOVEMENT_SPEED, new AttributeModifier(new NamespacedKey(plugin, "cultist_axe_weight"), SLOW, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.MAINHAND));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(headsKey, PersistentDataType.INTEGER, 0);
        dress(m);
        it.setItemMeta(m);
        return it;
    }

    /** lore and texture follow the head count */
    private void dress(ItemMeta m) {
        int heads = m.getPersistentDataContainer().getOrDefault(headsKey, PersistentDataType.INTEGER, 0);
        int stage = 0;
        for (int s : STAGES) if (heads >= s) stage++;
        m.setItemModel(new NamespacedKey("soulbound", "cultist_axe_" + stage));
        Component next = Component.text(stage < STAGES.length ? "Moloch hungers. " + (STAGES[stage] - heads) + " more." : "Moloch is fed.", NamedTextColor.DARK_GRAY);
        m.lore(List.of(
                Component.text("Moloch takes what it cuts.", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, true),
                Component.empty(),
                Component.text("Beheading: ", NamedTextColor.RED).append(Component.text("kills with it take the head.", NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false),
                Component.text("Executioner: ", NamedTextColor.RED).append(Component.text("+25% damage below 30% health.", NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false),
                Component.text("Headhunter: ", NamedTextColor.RED).append(Component.text("beheading a player marks you for 2 minutes.", NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Heads taken: " + heads, heads >= STAGES[STAGES.length - 1] ? NamedTextColor.GOLD : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                next.decoration(TextDecoration.ITALIC, false)));
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** a villager head taken with the axe (what Moloch's fire turns into a Blood Emerald) */
    boolean isVillagerHead(ItemStack it) {
        return it != null && it.getType() == Material.PLAYER_HEAD && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(villagerHeadKey, PersistentDataType.BYTE);
    }

    String adminGive(Player to) {
        for (ItemStack left : to.getInventory().addItem(item()).values()) to.getWorld().dropItem(to.getLocation(), left);
        return "Gave " + to.getName() + " the " + NAME + ".";
    }

    // ---------------------------------------------------------------- Executioner

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity v)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!is(p.getInventory().getItemInMainHand())) return;
        AttributeInstance max = v.getAttribute(Attribute.MAX_HEALTH);
        if (max == null || v.getHealth() >= max.getValue() * EXECUTE_BELOW) return;
        e.setDamage(e.getDamage() * EXECUTE_BONUS);
    }

    // ---------------------------------------------------------------- Beheading

    /** the player who struck the killing blow with the axe in hand, in melee (an arrow fired while holding it doesn't count) */
    private Player beheader(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || !is(k.getInventory().getItemInMainHand())) return null;
        DamageSource src = e.getDamageSource();
        if (src.getDirectEntity() != k || src.getCausingEntity() != k) return null;
        return k;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player killer = beheader(e);
        if (killer == null) return;
        Player victim = e.getPlayer();
        ItemStack axe = killer.getInventory().getItemInMainHand();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setPlayerProfile(victim.getPlayerProfile());
        trophy(sm, killer);
        head.setItemMeta(sm);
        give(killer, head);

        Component k = Component.text(killer.getName()), v = Component.text(victim.getName()), w = axe.displayName();
        Component msg = switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0 -> k.append(Component.text(" violently beheaded ")).append(v).append(Component.text(" using ")).append(w);
            case 1 -> v.append(Component.text(" lost their head to ")).append(k).append(Component.text("'s ")).append(w);
            default -> k.append(Component.text(" took ")).append(v).append(Component.text("'s head for Moloch"));
        };
        e.deathMessage(msg.color(NamedTextColor.DARK_RED));

        killer.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, GLOW_TICKS, 0, false, false, true));
        headhunters.put(killer.getUniqueId(), System.currentTimeMillis() + GLOW_TICKS * 50L);
        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.6f);
        count(killer);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVillagerDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Villager)) return;
        Player killer = beheader(e);
        if (killer == null) return;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        sm.setPlayerProfile(villagerProfile);
        sm.displayName(Component.text("Villager Head", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        sm.getPersistentDataContainer().set(villagerHeadKey, PersistentDataType.BYTE, (byte) 1);   // Moloch's fire takes these
        trophy(sm, killer);
        head.setItemMeta(sm);
        give(killer, head);
        killer.sendActionBar(Component.text("You took a villager's head. Moloch is pleased.", NamedTextColor.DARK_RED));
        count(killer);
    }

    private void trophy(ItemMeta m, Player killer) {
        m.lore(List.of(
                Component.text("Taken by " + killer.getName(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("with the " + NAME, NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false),
                Component.text(LocalDate.now().format(DATE), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    /** +1 head on the axe in the killer's hand; at 10, 25 and 50 it gets bloodier */
    private void count(Player killer) {
        ItemStack axe = killer.getInventory().getItemInMainHand();
        if (!is(axe)) return;
        ItemMeta m = axe.getItemMeta();
        int before = m.getPersistentDataContainer().getOrDefault(headsKey, PersistentDataType.INTEGER, 0), heads = before + 1;
        m.getPersistentDataContainer().set(headsKey, PersistentDataType.INTEGER, heads);
        dress(m);
        axe.setItemMeta(m);
        for (int s : STAGES) if (heads == s) {
            killer.sendMessage(Component.text("The axe drinks deeper. " + heads + " heads.", NamedTextColor.DARK_RED));
            killer.playSound(killer.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.7f);
        }
    }

    // ---------------------------------------------------------------- Headhunter: milk won't wash the mark off

    @EventHandler(ignoreCancelled = true)
    public void onEffectRemoved(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityPotionEffectEvent.Cause.MILK) return;
        if (e.getModifiedType() != PotionEffectType.GLOWING) return;
        Long until = headhunters.get(p.getUniqueId());
        if (until == null) return;
        if (until < System.currentTimeMillis()) { headhunters.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
    }
}
