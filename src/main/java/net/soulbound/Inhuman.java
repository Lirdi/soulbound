package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Heart of Darkness and what eating it makes you: no longer human.
 *
 * Monsters leave you be (unless you strike them), you carry two more hearts, and in Moloch's realm you have Fire
 * Resistance and Speed. Raw meat feeds and fills you; rotten flesh no longer sickens you; every other food leaves you
 * hungrier. But the living hate you: livestock, wolves and cats attack, iron golems hunt you, villagers flee unless
 * you hide your face under a Hood (and then they charge more), and only zombie villagers trade with you freely.
 * You can't sleep, the sun burns a bare head, and Heaven will never let you in again.
 */
final class Inhuman implements Listener {
    private static final Particle.DustOptions DARK = new Particle.DustOptions(Color.fromRGB(70, 6, 14), 0.9f);
    private final Soulbound plugin;
    private final CaptureManager captures;
    private final NamespacedKey key, heartKey, hpKey;
    private final Map<UUID, Long> provoked = new HashMap<>();      // monsters an inhuman player has struck
    private final Map<UUID, Long> lastBite = new HashMap<>();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private final Map<UUID, Long> lastSunWarn = new HashMap<>();
    private final Map<UUID, List<MerchantRecipe>> zombieStock = new HashMap<>();
    private final Map<Merchant, Object> merchants = new java.util.WeakHashMap<>();   // our merchant -> the villager / zombie it speaks for
    private int tick;

    Inhuman(Soulbound plugin, CaptureManager captures) {
        this.plugin = plugin;
        this.captures = captures;
        this.key = new NamespacedKey(plugin, "inhuman");
        this.heartKey = new NamespacedKey(plugin, "heart_of_darkness");
        this.hpKey = new NamespacedKey(plugin, "inhuman_hearts");
    }

    void start() { Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 5L); }

    // ---------------------------------------------------------------- the item and the state

    ItemStack heart() {
        ItemStack it = new ItemStack(Material.BEEF);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Heart of Darkness", NamedTextColor.DARK_RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Still beating. Not for long.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, true),
                Component.text("Eat it, and stop being human.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Heaven will never let you in again.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "heart_of_darkness"));
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

    boolean isHeart(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(heartKey, PersistentDataType.BYTE);
    }

    boolean is(Player p) { return p.getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    void set(Player p, boolean inhuman) {
        if (inhuman) p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        else p.getPersistentDataContainer().remove(key);
        applyHearts(p);
    }

    private void applyHearts(Player p) {
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp == null) return;
        hp.removeModifier(hpKey);
        if (is(p)) hp.addModifier(new AttributeModifier(hpKey, plugin.getConfig().getDouble("inhuman.extra-health", 4), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        else if (p.getHealth() > hp.getValue()) p.setHealth(hp.getValue());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { applyHearts(e.getPlayer()); }

    // ---------------------------------------------------------------- eating

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        ItemStack food = e.getItem();
        if (isHeart(food)) { Bukkit.getScheduler().runTask(plugin, () -> transform(p)); return; }
        if (plugin.grace() != null && plugin.grace().isHeart(food)) return;
        if (!is(p) || !food.getType().isEdible()) return;
        boolean tartare = plugin.tartare() != null && food.isSimilar(plugin.tartare().create(1));
        Material t = food.getType();
        boolean raw = tartare || switch (t) {
            case BEEF, PORKCHOP, MUTTON, CHICKEN, RABBIT, COD, SALMON, TROPICAL_FISH -> true;
            default -> false;
        };
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (raw) {
                int bonusFood = plugin.getConfig().getInt("inhuman.raw-meat-bonus-food", 3);
                float bonusSat = (float) plugin.getConfig().getDouble("inhuman.raw-meat-bonus-saturation", 4.0);
                p.setFoodLevel(Math.min(20, p.getFoodLevel() + bonusFood));
                p.setSaturation(Math.min(p.getFoodLevel(), p.getSaturation() + bonusSat));
                p.removePotionEffect(PotionEffectType.HUNGER);
                p.removePotionEffect(PotionEffectType.POISON);
            } else if (t == Material.ROTTEN_FLESH) {
                p.removePotionEffect(PotionEffectType.HUNGER);
            } else {
                p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 30 * 20, 1, false, true, true));
                p.sendActionBar(Component.text("It tastes of ash. You're hungrier than before.", NamedTextColor.DARK_RED));
            }
        });
    }

    private void transform(Player p) {
        if (!p.isOnline()) return;
        if (is(p)) { p.sendActionBar(Component.text("There's nothing left in you to lose.", NamedTextColor.DARK_RED)); return; }
        boolean wasGraced = plugin.grace() != null && plugin.grace().is(p);
        if (wasGraced) plugin.grace().set(p, false);
        set(p, true);
        if (wasGraced) p.sendMessage(Component.text("The light goes out of you.", NamedTextColor.GRAY, TextDecoration.ITALIC));
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
        p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", 1.2f, 1.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", 1.0f, 0.8f), 14L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.playSound(p.getLocation(), "minecraft:entity.warden.heartbeat", 0.8f, 0.6f), 34L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.playSound(p.getLocation(), "minecraft:block.beacon.deactivate", 1f, 0.5f), 60L);
        whisper(p, "Shh. Let it stop. Let it all stop.", 10L);
        whisper(p, "Welcome home, brother.", 80L);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0, DARK);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            grant(p, "sacrifice:moloch/inhuman");
            p.sendMessage(Component.text("You are no longer human. Monsters know you now; the living don't. Heaven's gates are shut to you.", NamedTextColor.DARK_RED));
        }, 90L);
    }

    private void whisper(Player p, String line, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Component.text("The Blood Priest whispers: ", NamedTextColor.DARK_RED, TextDecoration.ITALIC)
                    .append(Component.text(line, NamedTextColor.RED, TextDecoration.ITALIC)));
        }, delay);
    }

    private static void grant(Player p, String id) {
        NamespacedKey k = NamespacedKey.fromString(id);
        org.bukkit.advancement.Advancement a = k == null ? null : Bukkit.getAdvancement(k);
        if (a == null) return;
        var pr = p.getAdvancementProgress(a);
        for (String c : pr.getRemainingCriteria()) pr.awardCriteria(c);
    }

    // ---------------------------------------------------------------- monsters know you

    private static boolean boss(Entity e) {
        return e instanceof Warden || e instanceof Wither || e instanceof EnderDragon || e instanceof org.bukkit.entity.ElderGuardian
                || e.getScoreboardTags().stream().anyMatch(t -> t.startsWith("soulbound_"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !is(p) || !(e.getEntity() instanceof Enemy) || boss(e.getEntity())) return;
        Long when = provoked.get(e.getEntity().getUniqueId());
        if (when != null && System.currentTimeMillis() - when < 60_000) return;
        if (e.getReason() == EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStrike(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
        if (d instanceof Player p && is(p) && e.getEntity() instanceof Enemy) provoked.put(e.getEntity().getUniqueId(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- the living hate you; Moloch's realm loves you

    private static boolean livestock(Entity e) {
        return e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken || e instanceof Goat || e instanceof Rabbit
                || e instanceof Cat || (e instanceof AbstractHorse && !(e instanceof Llama));
    }

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        double range = plugin.getConfig().getDouble("inhuman.animal-range", 10);
        double dmg = plugin.getConfig().getDouble("inhuman.animal-damage", 2);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!is(p) || p.isDead()) continue;
            boolean playing = p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
            // Heaven won't have you
            if (p.getWorld().getEnvironment() == World.Environment.THE_END && playing && tick % 8 == 0) castOut(p);
            // his realm: fire resistance and speed
            if (tick % 8 == 0 && plugin.zones().inRealm(p.getLocation())) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 100, 0, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, true, false, true));
            }
            if (!playing) continue;
            if (tick % 2 == 0) sun(p, now);
            for (Entity e : p.getNearbyEntities(range, range / 2, range)) {
                if (e instanceof IronGolem g) { if (g.getTarget() == null) g.setTarget(p); continue; }
                if (e instanceof org.bukkit.entity.Snowman sg) { if (sg.getTarget() == null) sg.setTarget(p); continue; }
                if (e instanceof Wolf w) { if (w.getTarget() == null) { w.setAngry(true); w.setTarget(p); } continue; }
                if (!livestock(e) || !(e instanceof Mob m) || m.isDead()) continue;
                if (e instanceof Ageable a && !a.isAdult()) continue;
                if (m.isLeashed() || m.isInsideVehicle() || !m.getPassengers().isEmpty()) continue;
                if (tick % 2 == 0) m.getPathfinder().moveTo(p, 1.4);
                m.lookAt(p);
                if (tick % 4 == 0) m.getWorld().spawnParticle(Particle.DUST, m.getEyeLocation(), 2, 0.12, 0.05, 0.12, 0, DARK);
                double gap = m.getLocation().distance(p.getLocation()) - m.getWidth() / 2 - p.getWidth() / 2;
                if (gap < 0.8 && Math.abs(m.getLocation().getY() - p.getLocation().getY()) < 1.6) {
                    Long last = lastBite.get(m.getUniqueId());
                    if (last != null && now - last < 1000) continue;
                    lastBite.put(m.getUniqueId(), now);
                    p.damage((e instanceof Chicken || e instanceof Rabbit || e instanceof Cat) ? dmg / 2 : dmg, m);
                    Vector kb = p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
                    if (kb.lengthSquared() > 0.001) p.setVelocity(p.getVelocity().add(kb.normalize().multiply(0.3).setY(0.2)));
                }
            }
        }
        if (tick % 240 == 0) {
            lastBite.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
            provoked.values().removeIf(t -> now - t > 60_000);
        }
    }

    /**
     * The sun burns the inhuman, like the undead: out under the open sky in daylight with nothing on your head, you catch
     * fire. Anything worn on the head shades you (a Hood, a helmet, a pumpkin, a mob head...), and it never wears out.
     * Shade, rain, water, night, and the worlds without a sun are all safe.
     */
    private void sun(Player p, long now) {
        if (!plugin.getConfig().getBoolean("inhuman.sun-burns", true)) return;
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || !w.isDayTime() || !w.isClearWeather()) return;
        if (p.isInWater() || p.isInRain() || p.isInPowderedSnow()) return;
        ItemStack head = p.getInventory().getHelmet();
        if (head != null && !head.getType().isAir()) return;
        if (p.getEyeLocation().getBlock().getLightFromSky() < 15) return;
        p.setFireTicks(Math.max(p.getFireTicks(), 160));
        Long last = lastSunWarn.get(p.getUniqueId());
        if (last == null || now - last > 20_000) {
            lastSunWarn.put(p.getUniqueId(), now);
            p.sendMessage(Component.text("The sun burns what you've become. Cover your head, or keep to the shade.", NamedTextColor.GOLD, TextDecoration.ITALIC));
        } else p.sendActionBar(Component.text("The sun burns you. Cover your head.", NamedTextColor.GOLD));
    }

    /** A snow golem's snowball stings the inhuman: half a heart and a moment's chill. */
    @EventHandler(ignoreCancelled = true)
    public void onSnowball(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Snowball ball) || !(ball.getShooter() instanceof org.bukkit.entity.Snowman golem)) return;
        if (!(e.getHitEntity() instanceof Player p) || !is(p)) return;
        p.damage(plugin.getConfig().getDouble("inhuman.snowball-damage", 1), golem);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
        p.setFreezeTicks(Math.min(p.getMaxFreezeTicks(), p.getFreezeTicks() + 40));
    }

    // ---------------------------------------------------------------- no Heaven

    private void warn(Player p, String line) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastWarn.put(p.getUniqueId(), now);
        p.sendMessage(Component.text("God: ", NamedTextColor.GOLD).append(Component.text(line, NamedTextColor.WHITE)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        if (!is(p) || e.getTo() == null || e.getTo().getWorld() == null || e.getTo().getWorld().getEnvironment() != World.Environment.THE_END) return;
        e.setCancelled(true);
        p.setVelocity(p.getLocation().getDirection().multiply(-0.9).setY(0.6));
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.08);
        p.playSound(p.getLocation(), "minecraft:block.beacon.deactivate", 1f, 1.4f);
        warn(p, pick("No. Not like that. Not in my house.", "Knock all you like.", "You gave that heart away. Don't come crying to my door."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!is(p) || e.getTo().getWorld() == null || e.getTo().getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (e.getFrom().getWorld() != null && e.getFrom().getWorld().getEnvironment() == World.Environment.THE_END) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        e.setCancelled(true);
        warn(p, "No. Not like that. Not in my house.");
    }

    private void castOut(Player p) {
        Location to = p.getRespawnLocation();
        if (to == null || to.getWorld() == null || to.getWorld().getEnvironment() == World.Environment.THE_END) to = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.getWorld().strikeLightningEffect(p.getLocation());
        p.teleport(to);
        warn(p, "Out. You know what you did.");
    }

    private static String pick(String... s) { return s[java.util.concurrent.ThreadLocalRandom.current().nextInt(s.length)]; }

    // ---------------------------------------------------------------- no sleep

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent e) {
        Player p = e.getPlayer();
        if (!is(p)) return;
        e.setCancelled(true);
        p.setRespawnLocation(e.getBed().getLocation().add(0.5, 0.6, 0.5), true);
        p.sendActionBar(Component.text("Monsters don't sleep. (Your spawn is set.)", NamedTextColor.DARK_RED));
    }

    // ---------------------------------------------------------------- villagers

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillager(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND || !is(p)) return;
        if (e.getRightClicked() instanceof ZombieVillager z) { e.setCancelled(true); tradeZombie(p, z); return; }
        if (!(e.getRightClicked() instanceof Villager v)) return;
        e.setCancelled(true);
        ItemStack helm = p.getInventory().getHelmet();
        boolean hooded = helm != null && (captures.items.isHood(helm) || captures.items.isWorn(helm));
        if (!hooded) {
            v.getWorld().playSound(v.getLocation(), "minecraft:entity.villager.hurt", 1f, 1.3f);
            Vector away = v.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0.001) v.setVelocity(away.normalize().multiply(0.6).setY(0.3));
            p.sendActionBar(Component.text("It screams and backs away. Hide your face.", NamedTextColor.DARK_RED));
            return;
        }
        if (v.getRecipes().isEmpty()) { p.sendActionBar(Component.text("It has nothing to sell.", NamedTextColor.GRAY)); return; }
        double mult = plugin.getConfig().getDouble("inhuman.hooded-price-multiplier", 1.5);
        List<MerchantRecipe> copy = new ArrayList<>();
        for (MerchantRecipe r : v.getRecipes()) copy.add(pricier(r, mult));
        Merchant m = Bukkit.createMerchant();
        m.setRecipes(copy);
        merchants.put(m, v);
        p.openInventory(MenuType.MERCHANT.builder().merchant(m).title(Component.text("A nervous villager", NamedTextColor.GRAY)).build(p));
    }

    private static MerchantRecipe pricier(MerchantRecipe r, double mult) {
        MerchantRecipe n = new MerchantRecipe(r.getResult(), r.getUses(), r.getMaxUses(), false);
        List<ItemStack> ing = new ArrayList<>();
        for (int i = 0; i < r.getIngredients().size(); i++) {
            ItemStack s = r.getIngredients().get(i).clone();
            if (i == 0) s.setAmount(Math.min(s.getMaxStackSize(), Math.max(s.getAmount() + 1, (int) Math.ceil(s.getAmount() * mult))));
            ing.add(s);
        }
        n.setIngredients(ing);
        return n;
    }

    /** The dead trade with the dead: a zombie villager sells what its old trade would have, to the inhuman only. */
    private void tradeZombie(Player p, ZombieVillager z) {
        Villager.Profession prof = z.getVillagerProfession();
        if (prof == null || prof == Villager.Profession.NONE || prof == Villager.Profession.NITWIT) {
            p.sendActionBar(Component.text("It groans at you. It has nothing to sell.", NamedTextColor.GRAY));
            return;
        }
        List<MerchantRecipe> stock = zombieStock.computeIfAbsent(z.getUniqueId(), id -> {
            Villager tmp = z.getWorld().spawn(z.getLocation(), Villager.class, v -> { v.setSilent(true); v.setAI(false); v.setInvulnerable(true); v.setPersistent(false); });
            List<MerchantRecipe> out = new ArrayList<>();
            try {
                tmp.setProfession(prof);
                tmp.setVillagerExperience(1);
                tmp.getRecipes();
                tmp.increaseLevel(2);
                for (MerchantRecipe r : tmp.getRecipes()) {
                    MerchantRecipe c = new MerchantRecipe(r.getResult(), 0, r.getMaxUses(), false);
                    c.setIngredients(r.getIngredients());
                    out.add(c);
                }
            } catch (Exception ignored) {
            } finally { tmp.remove(); }
            return out;
        });
        if (stock.isEmpty()) { p.sendActionBar(Component.text("It has nothing to sell.", NamedTextColor.GRAY)); return; }
        Merchant m = Bukkit.createMerchant();
        m.setRecipes(stock);
        merchants.put(m, z.getUniqueId());
        p.openInventory(MenuType.MERCHANT.builder().merchant(m).title(Component.text("A rotting merchant", NamedTextColor.DARK_GREEN)).build(p));
        z.getWorld().playSound(z.getLocation(), "minecraft:entity.zombie_villager.ambient", 1f, 0.8f);
    }

    /** When the trade window closes, carry the uses back so stock runs out like a real villager's. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory() instanceof org.bukkit.inventory.MerchantInventory inv)) return;
        Merchant m = inv.getMerchant();
        Object who = merchants.remove(m);
        if (who == null) return;
        List<MerchantRecipe> now = m.getRecipes();
        if (who instanceof Villager v && v.isValid()) {
            List<MerchantRecipe> real = new ArrayList<>(v.getRecipes());
            for (int i = 0; i < Math.min(real.size(), now.size()); i++) real.get(i).setUses(Math.max(real.get(i).getUses(), now.get(i).getUses()));
            v.setRecipes(real);
        } else if (who instanceof UUID id) {
            zombieStock.put(id, new ArrayList<>(now));
        }
    }

}
