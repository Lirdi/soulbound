package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.util.List;

/** Beef Tartare: raw beef and an egg, crafted together. Fills you up and keeps you full far longer than steak. */
final class Tartare implements Listener {
    private final Soulbound plugin;
    private final NamespacedKey recipeKey;

    Tartare(Soulbound plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "beef_tartare");
    }

    ItemStack create(int amount) {
        ItemStack it = new ItemStack(Material.COOKED_BEEF, amount);   // already a food that can't be cooked any further
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Beef Tartare", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Raw beef, an egg yolk, and a little nerve.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)));
        m.setItemModel(new NamespacedKey("soulbound", "beef_tartare"));
        FoodComponent food = m.getFood();
        food.setNutrition(Math.max(1, plugin.getConfig().getInt("tartare.hunger", 8)));
        food.setSaturation((float) plugin.getConfig().getDouble("tartare.saturation", 16.0));
        m.setFood(food);
        it.setItemMeta(m);
        return it;
    }

    void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapelessRecipe r = new ShapelessRecipe(recipeKey, create(1));
        r.addIngredient(new RecipeChoice.MaterialChoice(Material.BEEF));
        r.addIngredient(new RecipeChoice.MaterialChoice(Material.EGG, Material.BROWN_EGG, Material.BLUE_EGG));
        r.setCategory(org.bukkit.inventory.recipe.CraftingBookCategory.MISC);
        Bukkit.addRecipe(r);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(recipeKey);
    }

    String adminGive(Player t, int n) {
        t.getInventory().addItem(create(n)).values().forEach(i -> t.getWorld().dropItem(t.getLocation(), i));
        return "Gave " + t.getName() + " " + n + " Beef Tartare.";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { e.getPlayer().discoverRecipe(recipeKey); }
}
