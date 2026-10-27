package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The tools of an abduction, beside the chain leash: the Hood (a burlap sack daubed with Moloch's sign, pulled over a
 * bound captive's head) and the Cultist's Cudgel (strike a weakened player from behind and they're dazed).
 */
final class AbductionItems {
    enum Kind { HOOD, CUDGEL }

    private final Soulbound plugin;
    private final NamespacedKey key, wornKey, stashKey;

    AbductionItems(Soulbound plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "abduction_item");
        this.wornKey = new NamespacedKey(plugin, "hood_worn");
        this.stashKey = new NamespacedKey(plugin, "hood_stash");
    }

    ItemStack create(Kind k, int amount) {
        ItemStack it = new ItemStack(k == Kind.HOOD ? Material.LEATHER : Material.WOODEN_SWORD, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        if (k == Kind.HOOD) {
            m.displayName(Component.text("Hood", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("A sack daubed with Moloch's sign.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                    Component.text("Use it on a bound captive: they see nothing,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("and nobody can tell who they are.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            m.setItemModel(new NamespacedKey("soulbound", "hood"));
        } else {
            m.displayName(Component.text("Cultist's Cudgel", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Hit them from behind.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                    Component.text("A weakened player struck from behind is dazed.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            m.setItemModel(new NamespacedKey("soulbound", "cudgel"));
        }
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, k.name());
        it.setItemMeta(m);
        return it;
    }

    /** The hood as it's worn, in the captive's real helmet slot; whatever helmet they had is kept inside it. */
    ItemStack worn(ItemStack stash) {
        ItemStack it = new ItemStack(Material.LEATHER);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Hood", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        m.setItemModel(new NamespacedKey("soulbound", "hood"));
        var eq = m.getEquippable();
        eq.setSlot(org.bukkit.inventory.EquipmentSlot.HEAD);
        eq.setEquipSound(org.bukkit.Sound.INTENTIONALLY_EMPTY);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(wornKey, PersistentDataType.BYTE, (byte) 1);
        if (stash != null && !stash.getType().isAir()) m.getPersistentDataContainer().set(stashKey, PersistentDataType.BYTE_ARRAY, stash.serializeAsBytes());
        it.setItemMeta(m);
        return it;
    }

    /** The chains as they're worn, in the chestplate slot (drawn as armour); their chestplate is kept inside. */
    ItemStack wornChains(ItemStack stash) {
        ItemStack it = new ItemStack(Material.IRON_NUGGET);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Chains", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        m.setItemModel(new NamespacedKey("soulbound", "shackles"));
        var eq = m.getEquippable();
        eq.setSlot(org.bukkit.inventory.EquipmentSlot.CHEST);
        eq.setModel(new NamespacedKey("soulbound", "shackles"));
        eq.setEquipSound(org.bukkit.Sound.INTENTIONALLY_EMPTY);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(wornKey, PersistentDataType.BYTE, (byte) 1);
        if (stash != null && !stash.getType().isAir()) m.getPersistentDataContainer().set(stashKey, PersistentDataType.BYTE_ARRAY, stash.serializeAsBytes());
        it.setItemMeta(m);
        return it;
    }

    boolean isWorn(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(wornKey, PersistentDataType.BYTE);
    }

    /** The helmet a worn hood was keeping, or null. */
    ItemStack stashOf(ItemStack worn) {
        if (!isWorn(worn)) return null;
        byte[] b = worn.getItemMeta().getPersistentDataContainer().get(stashKey, PersistentDataType.BYTE_ARRAY);
        if (b == null) return null;
        try { return ItemStack.deserializeBytes(b); } catch (Exception e) { return null; }
    }

    ItemStack shackles() {
        ItemStack it = new ItemStack(Material.IRON_NUGGET);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("soulbound", "shackles"));
        it.setItemMeta(m);
        return it;
    }

    Kind kindOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Kind.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    boolean isHood(ItemStack it) { return kindOf(it) == Kind.HOOD; }
    boolean isCudgel(ItemStack it) { return kindOf(it) == Kind.CUDGEL; }

    void registerRecipes() {
        add("hood", create(Kind.HOOD, 1), Material.WHEAT, Material.WHEAT, Material.WHEAT, Material.STRING, Material.RED_DYE);
        add("cudgel", create(Kind.CUDGEL, 1), Material.STICK, Material.COBBLESTONE, Material.COBBLESTONE, Material.GOLD_NUGGET);
    }

    private void add(String id, ItemStack result, Material... in) {
        NamespacedKey rk = new NamespacedKey(plugin, id);
        Bukkit.removeRecipe(rk);
        ShapelessRecipe r = new ShapelessRecipe(rk, result);
        for (Material mat : in) r.addIngredient(mat);
        Bukkit.addRecipe(r);
    }

    void unlockFor(Player p) {
        p.discoverRecipe(new NamespacedKey(plugin, "hood"));
        p.discoverRecipe(new NamespacedKey(plugin, "cudgel"));
    }

    String adminGive(Player t, String what) {
        Kind k = switch (what.toLowerCase(java.util.Locale.ROOT)) {
            case "hood", "sack" -> Kind.HOOD;
            case "cudgel", "club" -> Kind.CUDGEL;
            default -> null;
        };
        if (k == null) return "hood or cudgel";
        t.getInventory().addItem(create(k, k == Kind.HOOD ? 4 : 1));
        return "Gave " + t.getName() + " " + (k == Kind.HOOD ? "4 Hoods." : "a Cultist's Cudgel.");
    }
}
