package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cute head pieces anyone can craft. Cat Ears with a bow (they twitch now and then; creepers leave you alone, like
 * they do cats), Bunny Ears (they splay out when you hop; Jump Boost), a Flower Crown (bees never sting you, and
 * petals drift off it).
 */
final class Cute implements Listener {
    enum Kind {
        CAT("Cat Ears", "cat_ears", "cat_ears_twitch", "Creepers leave you alone, like they do cats."),
        BUNNY("Bunny Ears", "bunny_ears", "bunny_ears_hop", "Jump Boost I. Hop hop."),
        CROWN("Flower Crown", "flower_crown", null, "Bees will never sting you."),
        GUT("Bubble Gut", "bubble_gut", null, "Hard to push over. Harder to see your feet."),
        BRAIN("Big Brain", "big_brain_f0", null, "Brain power: +50% experience.");
        final String title, model, alt, perk;
        Kind(String title, String model, String alt, String perk) { this.title = title; this.model = model; this.alt = alt; this.perk = perk; }
    }
    private static final TextColor PINK = TextColor.color(0xF48FB1);

    private final Soulbound plugin;
    private final NamespacedKey key;
    private final Map<UUID, Integer> altUntil = new HashMap<>();
    private BukkitTask task;
    private int tick;

    Cute(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "cute"); }

    ItemStack create(Kind k) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(k.title, PINK, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text(k == Kind.GUT ? "Worn on the chest" : "Worn on the head", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text(k.perk, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", k.model));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(k == Kind.GUT ? EquipmentSlot.CHEST : EquipmentSlot.HEAD);
        if (k == Kind.GUT) {
            eq.setModel(new NamespacedKey("soulbound", "bubble_gut"));
            m.addAttributeModifier(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "bubble_gut_kb"), 0.5, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.CHEST));
        }
        eq.setEquipSound(Sound.INTENTIONALLY_EMPTY);
        eq.setSwappable(true);
        eq.setDispensable(true);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, k.name());
        it.setItemMeta(m);
        return it;
    }

    Kind kindOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Kind.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    void registerRecipes() {
        add(Kind.CAT, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.PINK_DYE, Material.STRING);
        add(Kind.BUNNY, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.PINK_DYE, Material.RABBIT_HIDE);
        add(Kind.CROWN, Material.PINK_TULIP, Material.OXEYE_DAISY, Material.ALLIUM, Material.VINE);
        add(Kind.GUT, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.CAKE);
        add(Kind.BRAIN, Material.PINK_WOOL, Material.PINK_WOOL, Material.BOOK, Material.LAPIS_LAZULI);
    }

    private void add(Kind k, Material... in) {
        NamespacedKey rk = new NamespacedKey(plugin, k.model);
        Bukkit.removeRecipe(rk);
        ShapelessRecipe r = new ShapelessRecipe(rk, create(k));
        for (Material mat : in) r.addIngredient(new RecipeChoice.MaterialChoice(mat));
        Bukkit.addRecipe(r);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(rk);
    }

    String adminGive(Player t, String what) {
        Kind k = switch (what.toLowerCase(Locale.ROOT)) {
            case "catears", "cat" -> Kind.CAT;
            case "bunnyears", "bunny" -> Kind.BUNNY;
            case "flowercrown", "crown" -> Kind.CROWN;
            case "bubblegut", "gut" -> Kind.GUT;
            case "bigbrain", "brain" -> Kind.BRAIN;
            default -> null;
        };
        if (k == null) {
            for (Kind each : Kind.values()) t.getInventory().addItem(create(each));
            return "Gave " + t.getName() + " all the cute head pieces.";
        }
        t.getInventory().addItem(create(k)).values().forEach(i -> t.getWorld().dropItem(t.getLocation(), i));
        return "Gave " + t.getName() + " " + k.title + ".";
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L); }
    void stop() { if (task != null) task.cancel(); }

    private void tick() {
        tick += 2;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack h = p.getInventory().getHelmet();
            Kind k = kindOf(h);
            if (k == Kind.GUT) k = null;
            if (k == null) { altUntil.remove(p.getUniqueId()); continue; }
            UUID id = p.getUniqueId();
            boolean alt = altUntil.getOrDefault(id, 0) > tick;
            switch (k) {
                case CAT -> { if (!alt && r.nextInt(100) == 0) { altUntil.put(id, tick + 6); alt = true; } }   // a twitch every few seconds
                case BUNNY -> {
                    if (!p.isOnGround() && p.getVelocity().getY() > 0.25) { altUntil.put(id, tick + 8); alt = true; }   // a hop
                    if (tick % 20 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, 0, true, false, true));
                }
                case CROWN -> { if (r.nextInt(25) == 0) p.getWorld().spawnParticle(Particle.CHERRY_LEAVES, p.getEyeLocation().add(0, 0.45, 0), 1, 0.25, 0.05, 0.25, 0); }
            }
            String want = k == Kind.BRAIN ? "big_brain_f" + ((tick / 4) % 4)      // it throbs
                    : alt && k.alt != null ? k.alt : k.model;
            ItemMeta m = h.getItemMeta();
            NamespacedKey wk = new NamespacedKey("soulbound", want);
            if (!wk.equals(m.getItemModel())) { m.setItemModel(wk); h.setItemMeta(m); p.getInventory().setHelmet(h); }
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        Kind k = kindOf(p.getInventory().getHelmet());
        if (k == Kind.CAT && e.getEntity() instanceof Creeper) e.setCancelled(true);
        if (k == Kind.CROWN && e.getEntity() instanceof Bee) e.setCancelled(true);
    }

    /** Big Brain: more experience from every orb. */
    @EventHandler
    public void onXp(org.bukkit.event.player.PlayerExpChangeEvent e) {
        if (e.getAmount() > 0 && kindOf(e.getPlayer().getInventory().getHelmet()) == Kind.BRAIN)
            e.setAmount((int) Math.round(e.getAmount() * 1.5));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { for (Kind k : Kind.values()) e.getPlayer().discoverRecipe(new NamespacedKey(plugin, k.model)); }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (kindOf(it) != null) { e.getInventory().setResult(null); return; }
    }
}
