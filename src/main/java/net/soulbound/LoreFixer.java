package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Keeps item tooltips a sensible width: any lore line longer than about 32 characters is wrapped onto several lines,
 * keeping its colour and style. Works on new items (shop trades, crafting and smithing results) and on items players
 * already own (fixed when they join, pick something up, hold it, or open a chest). Also renames old Breath of Eden
 * masks to the Heavenly Respirator.
 */
final class LoreFixer implements Listener {
    static final int WIDTH = 32;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Soulbound plugin;

    LoreFixer(Soulbound plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- the wrapping

    /** Wrap every long line; returns the same list if nothing needed wrapping. */
    static List<Component> wrap(List<Component> lore) {
        if (lore == null) return null;
        boolean any = false;
        for (Component c : lore) if (PLAIN.serialize(c).length() > WIDTH) { any = true; break; }
        if (!any) return lore;
        List<Component> out = new ArrayList<>();
        for (Component c : lore) {
            String text = PLAIN.serialize(c);
            if (text.length() <= WIDTH) { out.add(c); continue; }
            Style style = c.style();
            if (c instanceof net.kyori.adventure.text.TextComponent tc && tc.content().isEmpty() && !c.children().isEmpty()) style = c.children().get(0).style();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > WIDTH) {
                    out.add(Component.text(line.toString()).style(style));
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) out.add(Component.text(line.toString()).style(style));
        }
        return out;
    }

    /** Tidy one item in place; returns true if it changed. */
    boolean fix(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        boolean changed = false;
        if (plugin.eden() != null && plugin.eden().is(it)) {
            Component name = Component.text(EdenMask.NAME, NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
            if (!Objects.equals(m.displayName(), name)) { m.displayName(name); changed = true; }
        }
        if (m.hasLore()) {
            List<Component> lore = m.lore(), w = wrap(lore);
            if (w != lore) { m.lore(w); changed = true; }
        }
        if (changed) it.setItemMeta(m);
        return changed;
    }

    /** A tidied copy, for building shop trades. */
    ItemStack fixed(ItemStack it) {
        if (it == null) return null;
        ItemStack c = it.clone();
        fix(c);
        return c;
    }

    /** A shop trade with its result tidied. */
    org.bukkit.inventory.MerchantRecipe fixed(org.bukkit.inventory.MerchantRecipe r) {
        ItemStack out = r.getResult().clone();
        if (!fix(out)) return r;
        var n = new org.bukkit.inventory.MerchantRecipe(out, r.getUses(), r.getMaxUses(), r.hasExperienceReward(), r.getVillagerExperience(),
                r.getPriceMultiplier(), r.getDemand(), r.getSpecialPrice(), r.shouldIgnoreDiscounts());
        n.setIngredients(r.getIngredients());
        return n;
    }

    private void fixAll(Inventory inv) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (fix(it)) inv.setItem(i, it);
        }
    }

    // ---------------------------------------------------------------- when

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        fixAll(e.getPlayer().getInventory());
        fixAll(e.getPlayer().getEnderChest());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        var inv = e.getPlayer().getInventory();
        ItemStack it = inv.getItem(e.getNewSlot());
        if (fix(it)) inv.setItem(e.getNewSlot(), it);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != org.bukkit.event.inventory.InventoryType.MERCHANT) fixAll(e.getInventory());
        if (e.getPlayer() instanceof Player p) fixAll(p.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        Item item = e.getItem();
        ItemStack it = item.getItemStack();
        if (fix(it)) item.setItemStack(it);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        ItemStack r = e.getInventory().getResult();
        if (fix(r)) e.getInventory().setResult(r);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmith(PrepareSmithingEvent e) {
        ItemStack r = e.getResult();
        if (fix(r)) e.setResult(r);
    }
}
