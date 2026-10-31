package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /sb (/soulbound): every admin control in one place, in groups.
 *
 *   /sb give <player> <item> [amount]     /sb items
 *   /sb player <player> curse|uncurse|inhuman|resetlimits|info
 *   /sb moloch awaken|stop|hunger|bloodmoon|status
 *   /sb god test|heaven
 *   /sb world gate|temple|setarrival|priest
 *   /sb reload   /sb help
 *   /sb release (any player: let your captive go)
 */
final class SbCommand implements CommandExecutor, TabCompleter, Listener {
    /** inMenu = false for old names kept only so /sb give still understands them (they'd show twice in /sb items). */
    record Item(String category, Supplier<List<ItemStack>> make, boolean inMenu) {
        Item(String category, Supplier<List<ItemStack>> make) { this(category, make, true); }
    }

    private final Soulbound plugin;
    private Map<String, Item> items;

    SbCommand(Soulbound plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------- the item registry

    private static List<ItemStack> one(ItemStack it) { return List.of(it); }

    Map<String, Item> items() {
        if (items != null) return items;
        Soulbound p = plugin;
        Map<String, Item> m = new LinkedHashMap<>();
        // abduction
        m.put("hood", new Item("Abduction", () -> one(p.captures().items.create(AbductionItems.Kind.HOOD, 1))));
        m.put("cudgel", new Item("Abduction", () -> one(p.captures().items.create(AbductionItems.Kind.CUDGEL, 1))));
        m.put("shackles", new Item("Abduction", () -> one(p.leash().create(1))));
        // Blood Priest
        m.put("beef_tartare", new Item("Blood Priest", () -> one(p.tartare().create(1))));
        m.put("thief_key", new Item("Sacrifice", () -> one(p.thiefKey().create())));
        m.put("festering_flesh", new Item("Blood Priest", () -> one(p.festering().create(1))));
        m.put("heart_of_darkness", new Item("Blood Priest", () -> one(p.inhuman().heart())));
        m.put("angel_engine", new Item("Grace", () -> one(p.grace().heart())));
        m.put("heart_of_light", new Item("Grace", () -> one(p.grace().heart()), false));
        m.put("heavenly_respirator", new Item("Heaven", () -> one(p.eden().create())));
        m.put("breath_of_eden", new Item("Heaven", () -> one(p.eden().create()), false));
        // Moloch
        for (MolochGear.Piece pc : MolochGear.Piece.values())
            m.put("moloch_" + pc.name().toLowerCase(Locale.ROOT), new Item("Moloch", () -> one(p.gear().piece(pc))));
        m.put("moloch_set", new Item("Moloch", () -> { List<ItemStack> l = new ArrayList<>(); for (MolochGear.Piece pc : MolochGear.Piece.values()) l.add(p.gear().piece(pc)); return l; }));
        m.put("horns", new Item("Moloch", () -> one(p.gear().horns(null))));
        m.put("skull_of_mictlan", new Item("Moloch", () -> one(p.gear().skull(null))));
        m.put("viking_helm", new Item("Moloch", () -> one(p.gear().viking(null))));
        // God
        String[] anc = {"ancient_hood", "ancient_robe", "ancient_skirts", "ancient_sandals"};
        GodGear.Piece[] gp = GodGear.Piece.values();
        for (int i = 0; i < gp.length && i < anc.length; i++) { GodGear.Piece pc = gp[i]; m.put(anc[i], new Item("God", () -> one(p.godGear().piece(pc)))); }
        m.put("ancient_set", new Item("God", () -> { List<ItemStack> l = new ArrayList<>(); for (GodGear.Piece pc : GodGear.Piece.values()) l.add(p.godGear().piece(pc)); return l; }));
        m.put("halo", new Item("God", () -> one(p.halo().create(null))));
        // Quantum Humanity
        for (QuantumGear.Piece pc : QuantumGear.Piece.values())
            m.put("quantum_" + pc.name().toLowerCase(Locale.ROOT), new Item("Quantum", () -> one(p.quantum().piece(pc))));
        m.put("quantum_set", new Item("Quantum", () -> { List<ItemStack> l = new ArrayList<>(); for (QuantumGear.Piece pc : QuantumGear.Piece.values()) l.add(p.quantum().piece(pc)); return l; }));
        for (QuantumGear.Tool t : QuantumGear.Tool.values())
            m.put("quantum_" + t.name().toLowerCase(Locale.ROOT), new Item("Quantum", () -> one(p.quantum().tool(t))));
        m.put("quantum_tools", new Item("Quantum", () -> { List<ItemStack> l = new ArrayList<>(); for (QuantumGear.Tool t : QuantumGear.Tool.values()) l.add(p.quantum().tool(t)); return l; }));
        m.put("quantum_core", new Item("Quantum", () -> one(p.quantum().material(QuantumGear.Mat.CORE))));
        m.put("ember_of_moloch", new Item("Quantum", () -> one(p.quantum().material(QuantumGear.Mat.EMBER))));
        m.put("tear_of_god", new Item("Quantum", () -> one(p.quantum().material(QuantumGear.Mat.TEAR))));
        // the Egg of Potential's weapons
        m.put("blade_of_potential", new Item("Potential", () -> one(p.potentialBlade().item())));
        m.put("spiked_cultist_axe", new Item("Moloch", () -> one(p.cultistAxe().item())));
        m.put("blood_emerald", new Item("Moloch", () -> one(p.bloodEmeralds().create(1))));
        m.put("tumour_seed", new Item("Moloch", () -> one(p.tumourCrop().seed(1))));
        m.put("tumour", new Item("Moloch", () -> one(p.tumourCrop().tumour(1))));
        m.put("fleshy_potion", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.FLESHY, 1, false, Material.POTION))));
        m.put("potion_of_growth", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.GROWTH, 1, false, Material.POTION))));
        m.put("potion_of_growth_ii", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.GROWTH, 2, false, Material.POTION))));
        m.put("splash_potion_of_growth", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.GROWTH, 1, false, Material.SPLASH_POTION))));
        m.put("lingering_potion_of_growth", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.GROWTH, 1, false, Material.LINGERING_POTION))));
        m.put("potion_of_shrinking", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.SHRINK, 1, false, Material.POTION))));
        m.put("splash_potion_of_shrinking", new Item("Moloch", () -> one(p.growthPotions().potion(GrowthPotions.Kind.SHRINK, 1, false, Material.SPLASH_POTION))));
        // cute things
        String[][] cute = {{"cat_ears", "CAT"}, {"bunny_ears", "BUNNY"}, {"flower_crown", "CROWN"}, {"bubble_gut", "GUT"}, {"big_brain", "BRAIN"},
                {"chef_hat", "CHEF"}, {"wizard_hat", "WIZARD"}, {"top_hat", "TOPHAT"}, {"prospector_hat", "PROSPECTOR"}, {"tomato_helmet", "TOMATO"}, {"baroque", "BAROQUE"}, {"crocodile_mask", "CROC"}, {"propeller_cap", "PROPELLER"}, {"blood_priest_hat", "BP_HAT"},
                {"lab_coat", "LAB_COAT"}, {"lab_pants", "LAB_PANTS"}, {"lab_shoes", "LAB_SHOES"}};
        for (String[] c : cute) { Cute.Kind k = Cute.Kind.valueOf(c[1]); m.put(c[0], new Item("Wearables", () -> one(p.cute().create(k)))); }
        m.put("lab_outfit", new Item("Wearables", () -> List.of(p.cute().create(Cute.Kind.LAB_COAT), p.cute().create(Cute.Kind.LAB_PANTS), p.cute().create(Cute.Kind.LAB_SHOES))));
        // parasites
        m.put("black_filament", new Item("Parasites", () -> one(p.parasite().create())));
        m.put("cortex_leech", new Item("Parasites", () -> one(p.parasite().crown())));
        items = m;
        return m;
    }

    private String give(Player to, String id, int amount) {
        Item it = items().get(id.toLowerCase(Locale.ROOT));
        if (it == null) return null;
        int given = 0;
        for (int n = 0; n < Math.max(1, amount); n++) {
            for (ItemStack s : it.make().get()) {
                to.getInventory().addItem(s).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
                given++;
            }
        }
        return "Gave " + to.getName() + " " + (amount > 1 ? amount + "× " : "") + id + (given > 1 && amount <= 1 ? " (" + given + " items)" : "") + ".";
    }

    // ---------------------------------------------------------------- the items menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        int page;
        boolean more;
        final List<String> ids = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int PER_PAGE = 45;   // five rows of items; the bottom row turns the pages

    private void openMenu(Player p) { openMenu(p, 0); }

    private void openMenu(Player p, int page) {
        List<Map.Entry<String, Item>> shown = new ArrayList<>();
        for (Map.Entry<String, Item> e : items().entrySet()) if (e.getValue().inMenu()) shown.add(e);
        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu();
        menu.page = page;
        menu.more = page < pages - 1;
        Inventory inv = Bukkit.createInventory(menu, 54, Component.text("Soulbound items" + (pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : ""), NamedTextColor.DARK_RED));
        menu.inv = inv;
        if (page > 0) inv.setItem(45, button(Material.ARROW, "Previous page"));
        if (menu.more) inv.setItem(53, button(Material.ARROW, "Next page"));
        for (Map.Entry<String, Item> e : shown.subList(page * PER_PAGE, Math.min(shown.size(), (page + 1) * PER_PAGE))) {
            List<ItemStack> made = e.getValue().make().get();
            if (made.isEmpty()) continue;
            ItemStack icon = made.get(0).clone();
            ItemMeta im = icon.getItemMeta();
            if (made.size() > 1) {   // a whole set: say so, so it isn't mistaken for its first piece
                String nice = java.util.Arrays.stream(e.getKey().split("_")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(java.util.stream.Collectors.joining(" "));
                im.displayName(Component.text(nice + " (all " + made.size() + ")", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                im.setEnchantmentGlintOverride(true);
            }
            List<Component> lore = im.hasLore() && im.lore() != null ? new ArrayList<>(im.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text(e.getValue().category() + " · " + e.getKey() + (made.size() > 1 ? " (" + made.size() + " pieces)" : ""), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Click to take", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            im.lore(lore);
            icon.setItemMeta(im);
            inv.setItem(menu.ids.size(), icon);
            menu.ids.add(e.getKey());
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot == 45 && menu.page > 0) { openMenu(p, menu.page - 1); return; }
        if (slot == 53 && menu.more) { openMenu(p, menu.page + 1); return; }
        if (slot < 0 || slot >= menu.ids.size()) return;
        give(p, menu.ids.get(slot), e.isShiftClick() ? 4 : 1);
        p.playSound(p.getLocation(), "minecraft:entity.item.pickup", 0.6f, 1.2f);
    }

    private static ItemStack button(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(im);
        return it;
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- the command

    private static Component ok(String s) { return Component.text(s, NamedTextColor.GREEN); }
    private static Component bad(String s) { return Component.text(s, NamedTextColor.RED); }

    private static Component line(String cmd, String what) {
        String fill = cmd.contains("<") ? cmd.substring(0, cmd.indexOf('<')) : cmd;
        return Component.text(cmd, NamedTextColor.GOLD).clickEvent(ClickEvent.suggestCommand(fill))
                .hoverEvent(HoverEvent.showText(Component.text("Click to fill in", NamedTextColor.GRAY)))
                .append(Component.text("  " + what, NamedTextColor.GRAY));
    }

    private void help(CommandSender s, String group) {
        boolean admin = s.hasPermission("soulbound.admin");
        s.sendMessage(Component.text("— Soulbound —", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        if (!admin) { s.sendMessage(line("/sb release", "let your captive go")); return; }
        if (group == null || group.equals("give")) {
            s.sendMessage(line("/sb give <player> <item> [amount]", "any custom item (tab to see them all)"));
            s.sendMessage(line("/sb items", "a menu of every custom item: click to take"));
        }
        if (group == null || group.equals("player")) {
            s.sendMessage(line("/sb player <player> info", "curse, inhuman, sacrifices, Priest unlocks"));
            s.sendMessage(line("/sb player <player> curse [minutes]", "Moloch's curse"));
            s.sendMessage(line("/sb player <player> uncurse", "lift it (and they can be offered again)"));
            s.sendMessage(line("/sb player <player> inhuman [on|off]", "the Heart of Darkness, without eating it"));
            s.sendMessage(line("/sb player <player> grace [on|off]", "the Angel Engine's blessing, without eating it"));
            s.sendMessage(line("/sb player <player> resetlimits", "sacrifice cooldown and daily count"));
        }
        if (group == null || group.equals("moloch")) {
            s.sendMessage(line("/sb moloch status", "how hungry he is"));
            s.sendMessage(line("/sb moloch awaken", "raise him now"));
            s.sendMessage(line("/sb moloch stop", "end the fight"));
            s.sendMessage(line("/sb moloch hunger <0-100>", "set his Hunger"));
            s.sendMessage(line("/sb moloch bloodmoon <hours|off>", "start or end a blood moon"));
            s.sendMessage(line("/sb moloch resetlimits", "every sacrifice cooldown and today's limits"));
        }
        if (group == null || group.equals("god")) {
            s.sendMessage(line("/sb god test", "God's test spawn"));
            s.sendMessage(line("/sb god heaven", "rebuild the throne, gates and pillars"));
        }
        if (group == null || group.equals("world")) {
            s.sendMessage(line("/sb world priest [remove]", "place (or remove) the Blood Priest here"));
            s.sendMessage(line("/sb world gate [here]", "rebuild Moloch's Exit Gate (or move it here)"));
            s.sendMessage(line("/sb world temple", "rebuild the pyramid stairs and side gates"));
            s.sendMessage(line("/sb world setarrival", "portals into his realm arrive where you stand"));
            s.sendMessage(line("/sb world zephyr", "a Zephyr (Heaven's breeze) in front of you"));
            s.sendMessage(line("/sb world peacekeeper [remove]", "place (or remove) the Peacekeeper here"));
        }
        if (group == null) {
            s.sendMessage(line("/sb reload", "reload config.yml"));
            s.sendMessage(line("/sb release", "let your captive go"));
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "help";
        if (sub.equals("release")) return plugin.legacySoulbound(s, a);
        if (!s.hasPermission("soulbound.admin")) { help(s, null); return true; }
        switch (sub) {
            case "help" -> help(s, a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : null);
            case "items" -> { if (s instanceof Player p) openMenu(p); else s.sendMessage("Players only."); }
            case "give" -> {
                if (a.length < 2) { help(s, "give"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(bad("No player online called " + a[1] + ".")); return true; }
                // the old form: /soulbound give <player> [amount] gave shackles
                if (a.length < 3 || a[2].matches("\\d+")) { s.sendMessage(ok(give(t, "shackles", a.length > 2 ? Integer.parseInt(a[2]) : 1))); return true; }
                int n = 1;
                if (a.length > 3) try { n = Math.max(1, Math.min(64, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) {}
                String r = give(t, a[2], n);
                s.sendMessage(r != null ? ok(r) : bad("No item called " + a[2] + ". Press Tab after /sb give " + t.getName() + " to see them all, or use /sb items."));
            }
            case "player" -> player(s, a);
            case "moloch" -> {
                String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                switch (what) {
                    case "status" -> plugin.legacyMoloch(s, new String[0]);
                    case "awaken", "stop", "resetlimits" -> plugin.legacyMoloch(s, new String[]{what});
                    case "hunger", "bloodmoon" -> plugin.legacyMoloch(s, a.length > 2 ? new String[]{what, a[2]} : new String[]{what});
                    default -> help(s, "moloch");
                }
            }
            case "god" -> {
                String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                switch (what) {
                    case "test" -> plugin.legacyMoloch(s, new String[]{"god"});
                    case "heaven" -> plugin.legacyMoloch(s, new String[]{"heaven"});
                    default -> help(s, "god");
                }
            }
            case "world" -> {
                String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                switch (what) {
                    case "priest", "gate" -> plugin.legacyMoloch(s, a.length > 2 ? new String[]{what, a[2]} : new String[]{what});
                    case "temple", "setarrival" -> plugin.legacyMoloch(s, new String[]{what});
                    case "peacekeeper" -> {
                        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                        boolean remove = a.length > 2 && a[2].equalsIgnoreCase("remove");
                        s.sendMessage(ok(remove ? plugin.peacekeeper().removeNear(p) : plugin.peacekeeper().spawn(p)));
                    }
                    case "zephyr" -> {
                        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                        plugin.zephyrs().summon(p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4)));
                        s.sendMessage(ok("A Zephyr rises."));
                    }
                    default -> help(s, "world");
                }
            }
            case "reload" -> plugin.legacyMoloch(s, new String[]{"reload"});
            default -> help(s, null);
        }
        return true;
    }

    private void player(CommandSender s, String[] a) {
        if (a.length < 3) { help(s, "player"); return; }
        String name = a[1], what = a[2].toLowerCase(Locale.ROOT);
        if (what.equals("resetlimits")) { plugin.legacyMoloch(s, new String[]{"resetlimits", name}); return; }
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { s.sendMessage(bad("No player online called " + name + ".")); return; }
        switch (what) {
            case "curse" -> plugin.legacyMoloch(s, a.length > 3 ? new String[]{"curse", name, a[3]} : new String[]{"curse", name});
            case "uncurse" -> plugin.legacyMoloch(s, new String[]{"uncurse", name});
            case "inhuman" -> {
                boolean off = a.length > 3 && a[3].equalsIgnoreCase("off");
                plugin.inhuman().set(t, !off);
                s.sendMessage(ok(t.getName() + (off ? " is human again." : " is no longer human.")));
            }
            case "grace" -> {
                boolean off = a.length > 3 && a[3].equalsIgnoreCase("off");
                if (!off && plugin.inhuman().is(t)) plugin.inhuman().set(t, false);
                plugin.grace().set(t, !off);
                s.sendMessage(ok(t.getName() + (off ? " is no longer Graced." : " is Graced.")));
            }
            case "info" -> {
                s.sendMessage(Component.text("— " + t.getName() + " —", NamedTextColor.GOLD));
                int left = plugin.curses().secondsLeft(t);
                s.sendMessage(Component.text("Curse: " + (left > 0 ? CurseManager.clock(left) + " left" : "none"), NamedTextColor.GRAY));
                s.sendMessage(Component.text("Inhuman: " + (plugin.inhuman().is(t) ? "yes" : "no"), NamedTextColor.GRAY));
                s.sendMessage(Component.text("Graced: " + (plugin.grace().is(t) ? "yes" : "no"), NamedTextColor.GRAY));
                s.sendMessage(Component.text("Bought everything from the Priest: " + (plugin.priest().boughtAll(t) ? "yes (the Heart is unlocked)" : "not yet"), NamedTextColor.GRAY));
                s.sendMessage(Component.text("Has sacrificed someone: " + (plugin.store().data.getBoolean("sacrifice.first-done." + t.getUniqueId(), false) ? "yes" : "no"), NamedTextColor.GRAY));
                long last = plugin.store().data.getLong("sacrifice.victims." + t.getUniqueId(), 0);
                s.sendMessage(Component.text("Last offered as a victim: " + (last == 0 ? "never" : ((System.currentTimeMillis() - last) / 3_600_000) + " hours ago"), NamedTextColor.GRAY));
            }
            default -> help(s, "player");
        }
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("soulbound.admin")) return a.length == 1 ? filter(List.of("release"), a[0]) : List.of();
        if (a.length == 1) return filter(List.of("give", "items", "player", "moloch", "god", "world", "reload", "release", "help"), a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> {
                if (a.length == 2) return null;   // player names
                if (a.length == 3) return filter(new ArrayList<>(items().keySet()), a[2]);
                if (a.length == 4) return filter(List.of("1", "4", "16", "64"), a[3]);
            }
            case "player" -> {
                if (a.length == 2) return null;
                if (a.length == 3) return filter(List.of("info", "curse", "uncurse", "inhuman", "grace", "resetlimits"), a[2]);
                if (a.length == 4 && (a[2].equalsIgnoreCase("inhuman") || a[2].equalsIgnoreCase("grace"))) return filter(List.of("on", "off"), a[3]);
                if (a.length == 4 && a[2].equalsIgnoreCase("curse")) return filter(List.of("15", "45", "120"), a[3]);
            }
            case "moloch" -> {
                if (a.length == 2) return filter(List.of("status", "awaken", "stop", "hunger", "bloodmoon", "resetlimits"), a[1]);
                if (a.length == 3 && a[1].equalsIgnoreCase("bloodmoon")) return filter(List.of("1", "2", "off"), a[2]);
                if (a.length == 3 && a[1].equalsIgnoreCase("hunger")) return filter(List.of("0", "50", "95", "100"), a[2]);
            }
            case "god" -> { if (a.length == 2) return filter(List.of("test", "heaven"), a[1]); }
            case "world" -> {
                if (a.length == 2) return filter(List.of("priest", "peacekeeper", "gate", "temple", "setarrival", "zephyr"), a[1]);
                if (a.length == 3 && (a[1].equalsIgnoreCase("priest") || a[1].equalsIgnoreCase("peacekeeper"))) return filter(List.of("remove"), a[2]);
                if (a.length == 3 && a[1].equalsIgnoreCase("gate")) return filter(List.of("here"), a[2]);
            }
            case "help" -> { if (a.length == 2) return filter(List.of("give", "player", "moloch", "god", "world"), a[1]); }
            default -> { }
        }
        return List.of();
    }

    private static List<String> filter(List<String> opts, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(t) || o.contains("_" + t)) out.add(o);
        return out;
    }
}
