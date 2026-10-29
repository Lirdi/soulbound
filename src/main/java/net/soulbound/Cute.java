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
        GUT("Bubble Gut", "bubble_gut", null, "Hard to push over. Harder to see your feet.", EquipmentSlot.CHEST, 0),
        BRAIN("Big Brain", "big_brain_f0", null, "Brain power: +50% experience."),
        CHEF("Chef's Hat", "chef_hat", null, "Everything you eat fills you up for longer."),
        WIZARD("Wizard's Hat", "wizard_hat", null, "Enchanting costs one level less."),
        TOPHAT("Top Hat", "top_hat", null, "Villagers give a gentleman their best prices."),
        PROSPECTOR("Prospector's Hat", "prospector_hat", null, "Ores sometimes give a little extra."),
        TOMATO("Tomato Helmet", "tomato_helmet", null, "Get hit and you go splat."),
        BAROQUE("Baroque", "baroque", null, "A bauta and a tricorn, for the masquerade."),
        CROC("Crocodile Mask", "croc_mask", null, "It hasn't decided whether to swallow you."),
        LAB_COAT("Lab Coat", "lab_coat", null, "Lab outfit (1 of 3). Full outfit: potions last 50% longer.", EquipmentSlot.CHEST, 3),
        LAB_PANTS("Lab Trousers", "lab_pants", null, "Lab outfit (2 of 3). Full outfit: potions last 50% longer.", EquipmentSlot.LEGS, 2),
        LAB_SHOES("Lab Shoes", "lab_shoes", null, "Lab outfit (3 of 3). Full outfit: potions last 50% longer.", EquipmentSlot.FEET, 1);
        final String title, model, alt, perk;
        final EquipmentSlot slot;
        final int armor;
        Kind(String title, String model, String alt, String perk) { this(title, model, alt, perk, EquipmentSlot.HEAD, 0); }
        Kind(String title, String model, String alt, String perk, EquipmentSlot slot, int armor) {
            this.title = title; this.model = model; this.alt = alt; this.perk = perk; this.slot = slot; this.armor = armor;
        }
        boolean lab() { return this == LAB_COAT || this == LAB_PANTS || this == LAB_SHOES; }
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
        m.displayName(Component.text(k.title, k == Kind.BAROQUE ? TextColor.color(0xD6AC48) : k == Kind.CROC ? TextColor.color(0x7FA84A) : PINK, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        String where = switch (k.slot) { case CHEST -> "Worn on the chest"; case LEGS -> "Worn on the legs"; case FEET -> "Worn on the feet"; default -> "Worn on the head"; };
        m.lore(List.of(Component.text(where, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text(k.perk, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", k.model));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(k.slot);
        if (k.lab()) {
            eq.setModel(new NamespacedKey("soulbound", "labcoat"));
            org.bukkit.inventory.EquipmentSlotGroup g = switch (k.slot) { case CHEST -> org.bukkit.inventory.EquipmentSlotGroup.CHEST;
                case LEGS -> org.bukkit.inventory.EquipmentSlotGroup.LEGS; default -> org.bukkit.inventory.EquipmentSlotGroup.FEET; };
            m.addAttributeModifier(org.bukkit.attribute.Attribute.ARMOR, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, k.model + "_armor"), k.armor, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, g));
        }
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
        add(Kind.CHEF, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.BOWL);
        add(Kind.WIZARD, Material.BLUE_WOOL, Material.BLUE_WOOL, Material.BLUE_WOOL, Material.GLOWSTONE_DUST);
        add(Kind.TOPHAT, Material.BLACK_WOOL, Material.BLACK_WOOL, Material.BLACK_WOOL, Material.RED_DYE, Material.EMERALD);
        add(Kind.PROSPECTOR, Material.YELLOW_WOOL, Material.YELLOW_WOOL, Material.YELLOW_WOOL, Material.BLACK_DYE, Material.DIAMOND);
        add(Kind.LAB_COAT, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.WHITE_WOOL, Material.PAPER);
        add(Kind.LAB_PANTS, Material.GRAY_WOOL, Material.GRAY_WOOL, Material.GRAY_WOOL, Material.LEATHER);
        add(Kind.LAB_SHOES, Material.LEATHER, Material.LEATHER, Material.BLACK_DYE);
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
            case "chefhat", "chef" -> Kind.CHEF;
            case "wizardhat", "wizard" -> Kind.WIZARD;
            case "tophat", "top" -> Kind.TOPHAT;
            case "prospectorhat", "prospector", "minerhat", "miner" -> Kind.PROSPECTOR;
            case "tomatohelmet", "tomato" -> Kind.TOMATO;
            case "baroque", "bauta", "tricorn", "masquerade" -> Kind.BAROQUE;
            case "crocodilemask", "crocodile", "croc", "crocmask" -> Kind.CROC;
            case "labcoat", "coat" -> Kind.LAB_COAT;
            case "labpants", "trousers", "pants" -> Kind.LAB_PANTS;
            case "labshoes", "shoes" -> Kind.LAB_SHOES;
            case "lab" -> {
                for (Kind each : new Kind[]{Kind.LAB_COAT, Kind.LAB_PANTS, Kind.LAB_SHOES}) t.getInventory().addItem(create(each));
                yield null;
            }
            default -> null;
        };
        if (k == null) {
            if (what.equalsIgnoreCase("lab")) return "Gave " + t.getName() + " the lab outfit.";
            for (Kind each : Kind.values()) t.getInventory().addItem(create(each));
            return "Gave " + t.getName() + " all the cosmetics.";
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

    /** The Tomato Helmet: take a hit and tomato goes everywhere. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplat(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || kindOf(p.getInventory().getHelmet()) != Kind.TOMATO) return;
        org.bukkit.Location at = p.getEyeLocation().add(0, 0.3, 0);
        p.getWorld().spawnParticle(Particle.ITEM, at, 18, 0.35, 0.3, 0.35, 0.12, new ItemStack(Material.BEETROOT));
        p.getWorld().spawnParticle(Particle.DUST, at, 14, 0.4, 0.3, 0.4, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0xD62E1E), 1.4f));
        p.getWorld().playSound(at, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.8f, 1.3f);
        p.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 0.6f, 1.5f);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        Kind k = kindOf(p.getInventory().getHelmet());
        if (k == Kind.CAT && e.getEntity() instanceof Creeper) e.setCancelled(true);
        if (k == Kind.CROWN && e.getEntity() instanceof Bee) e.setCancelled(true);
    }

    /** The full lab outfit: potions you drink last half as long again. */
    @EventHandler(ignoreCancelled = true)
    public void onPotion(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (e.getCause() != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.POTION_DRINK || !(e.getEntity() instanceof Player p)) return;
        var inv = p.getInventory();
        if (kindOf(inv.getChestplate()) != Kind.LAB_COAT || kindOf(inv.getLeggings()) != Kind.LAB_PANTS || kindOf(inv.getBoots()) != Kind.LAB_SHOES) return;
        PotionEffect ne = e.getNewEffect();
        if (ne == null || ne.isInfinite() || ne.getDuration() < 20) return;
        e.setCancelled(true);
        PotionEffect longer = new PotionEffect(ne.getType(), (int) (ne.getDuration() * 1.5), ne.getAmplifier(), ne.isAmbient(), ne.hasParticles(), ne.hasIcon());
        Bukkit.getScheduler().runTask(plugin, () -> p.addPotionEffect(longer));
    }

    /** Chef's Hat: food keeps you full longer (a little extra saturation from every meal). */
    @EventHandler(ignoreCancelled = true)
    public void onEat(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (kindOf(p.getInventory().getHelmet()) != Kind.CHEF || !e.getItem().getType().isEdible()) return;
        Bukkit.getScheduler().runTask(plugin, () -> p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + 3f)));
    }

    /** Wizard's Hat: a level back after every enchantment. */
    @EventHandler(ignoreCancelled = true)
    public void onEnchant(org.bukkit.event.enchantment.EnchantItemEvent e) {
        Player p = e.getEnchanter();
        if (kindOf(p.getInventory().getHelmet()) != Kind.WIZARD) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.giveExpLevels(1);
            p.getWorld().spawnParticle(Particle.ENCHANT, p.getEyeLocation().add(0, 0.6, 0), 30, 0.4, 0.4, 0.4, 0.5);
        });
    }

    /** Top Hat: talk to a villager and they treat you like the Hero of the Village. */
    @EventHandler(ignoreCancelled = true)
    public void onVillager(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof org.bukkit.entity.AbstractVillager)) return;
        Player p = e.getPlayer();
        if (kindOf(p.getInventory().getHelmet()) != Kind.TOPHAT) return;
        PotionEffect cur = p.getPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        if (cur == null || cur.getAmplifier() < 1)
            p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 200, 1, true, false, false));
    }

    /** Prospector's Hat: a 15% chance of a doubled haul from ores. */
    @EventHandler(ignoreCancelled = true)
    public void onOre(org.bukkit.event.block.BlockDropItemEvent e) {
        Player p = e.getPlayer();
        if (kindOf(p.getInventory().getHelmet()) != Kind.PROSPECTOR) return;
        String t = e.getBlockState().getType().name();
        if (!t.endsWith("_ORE") && !t.equals("ANCIENT_DEBRIS")) return;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= 0.15) return;
        var loc = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (org.bukkit.entity.Item it : e.getItems()) loc.getWorld().dropItemNaturally(loc, it.getItemStack().clone());
        if (!e.getItems().isEmpty()) loc.getWorld().spawnParticle(Particle.WAX_OFF, loc, 12, 0.3, 0.3, 0.3, 0.5);
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
