package my.dangshop;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;

public class DangShop extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int SIZE = 54;
    private static final int BACK_SLOT = 49;
    private static final int MAX_ITEMS = 45;

    private static class Holder implements InventoryHolder {
        final String section; // null = main menu

        Holder(String section) {
            this.section = section;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class ShopItem {
        final Material mat;
        final double buy;
        final double sell;

        ShopItem(Material mat, double buy, double sell) {
            this.mat = mat;
            this.buy = buy;
            this.sell = sell;
        }
    }

    private final Map<String, List<ShopItem>> shops = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Material> icons = new HashMap<>();
    private final Map<String, Integer> slots = new HashMap<>();

    private Object eco;
    private Method getBalance;
    private Method withdraw;
    private Method deposit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadShops();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("dshop") != null) {
            getCommand("dshop").setExecutor(this);
            getCommand("dshop").setTabCompleter(this);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String msg(String key, String def) {
        return color(getConfig().getString("messages." + key, def));
    }

    // ---------- Load config ----------
    private void loadShops() {
        shops.clear();
        names.clear();
        icons.clear();
        slots.clear();

        ConfigurationSection secs = getConfig().getConfigurationSection("sections");
        if (secs == null) return;

        for (String id : secs.getKeys(false)) {
            ConfigurationSection s = secs.getConfigurationSection(id);
            if (s == null) continue;

            names.put(id, color(s.getString("name", id)));
            Material icon = Material.matchMaterial(s.getString("icon", "STONE"));
            icons.put(id, icon == null ? Material.STONE : icon);
            slots.put(id, s.getInt("slot", 10));

            List<ShopItem> list = new ArrayList<>();
            for (String line : s.getStringList("items")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 3) {
                    getLogger().warning("Invalid line in " + id + ": " + line);
                    continue;
                }
                Material m = Material.matchMaterial(parts[0]);
                if (m == null || !m.isItem()) {
                    getLogger().warning("Unknown item in " + id + ": " + parts[0]);
                    continue;
                }
                try {
                    list.add(new ShopItem(m, Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
                } catch (NumberFormatException ex) {
                    getLogger().warning("Invalid price in " + id + ": " + line);
                }
            }
            shops.put(id, list);
        }
    }

    // ---------- Command ----------
    private static final List<String> ADMIN_SUBS = List.of(
            "addsection", "removesection", "additem", "setprice", "removeitem", "list", "help");

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase();

            if (sub.equals("reload")) {
                if (!sender.hasPermission("dangshop.reload")) return true;
                reloadConfig();
                loadShops();
                sender.sendMessage(msg("reloaded", "&aConfig reloaded."));
                return true;
            }
            if (ADMIN_SUBS.contains(sub)) {
                return admin(sender, sub, args);
            }
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!p.hasPermission("dangshop.use")) return true;

        if (args.length > 0 && shops.containsKey(args[0].toLowerCase())) {
            openSection(p, args[0].toLowerCase());
            return true;
        }
        openMain(p);
        return true;
    }

    // ---------- Admin commands ----------
    private boolean admin(CommandSender s, String sub, String[] a) {
        if (!s.hasPermission("dangshop.admin")) {
            s.sendMessage(color("&cYou don't have permission."));
            return true;
        }

        switch (sub) {
            case "help" -> help(s);
            case "list" -> listCmd(s, a);
            case "addsection" -> addSection(s, a);
            case "removesection" -> removeSection(s, a);
            case "additem" -> addItem(s, a);
            case "setprice" -> setPrice(s, a);
            case "removeitem" -> removeItem(s, a);
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(color("&8--- &6DangShop Admin &8---"));
        s.sendMessage(color("&e/dshop list [section] &7- show sections or items"));
        s.sendMessage(color("&e/dshop addsection <id> <ICON> <slot> <name> &7- new section"));
        s.sendMessage(color("&e/dshop removesection <id> &7- delete a section"));
        s.sendMessage(color("&e/dshop additem <section> <MATERIAL|hand> <buy> <sell> &7- add item"));
        s.sendMessage(color("&e/dshop setprice <section> <MATERIAL|hand> <buy> <sell> &7- change price"));
        s.sendMessage(color("&e/dshop removeitem <section> <MATERIAL|hand> &7- remove item"));
        s.sendMessage(color("&7Use 0 as a price to disable buying or selling."));
    }

    private void listCmd(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(color("&8--- &6Sections &8---"));
            for (String id : shops.keySet()) {
                s.sendMessage(color("&e" + id + " &7- " + names.get(id) + " &7(slot "
                        + slots.get(id) + ", " + shops.get(id).size() + " items)"));
            }
            return;
        }
        String id = a[1].toLowerCase();
        List<ShopItem> list = shops.get(id);
        if (list == null) {
            s.sendMessage(color("&cSection not found: " + id));
            return;
        }
        s.sendMessage(color("&8--- &6" + id + " &8---"));
        for (ShopItem si : list) {
            s.sendMessage(color("&e" + si.mat.name() + " &7buy &a" + num(si.buy)
                    + " &7sell &c" + num(si.sell)));
        }
    }

    private void addSection(CommandSender s, String[] a) {
        if (a.length < 4) {
            s.sendMessage(color("&eUsage: /dshop addsection <id> <ICON> <slot> <name>"));
            return;
        }
        String id = a[1].toLowerCase();
        if (shops.containsKey(id)) {
            s.sendMessage(color("&cSection already exists: " + id));
            return;
        }
        Material icon = Material.matchMaterial(a[2]);
        if (icon == null || !icon.isItem()) {
            s.sendMessage(color("&cUnknown icon material: " + a[2]));
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(a[3]);
        } catch (NumberFormatException e) {
            s.sendMessage(color("&cSlot must be a number from 0 to 53."));
            return;
        }
        if (slot < 0 || slot >= SIZE) {
            s.sendMessage(color("&cSlot must be from 0 to 53."));
            return;
        }
        if (slots.containsValue(slot)) {
            s.sendMessage(color("&cThat slot is already used by another section."));
            return;
        }

        String name = a.length > 4 ? String.join(" ", Arrays.copyOfRange(a, 4, a.length)) : id;
        getConfig().set("sections." + id + ".name", name);
        getConfig().set("sections." + id + ".icon", icon.name());
        getConfig().set("sections." + id + ".slot", slot);
        getConfig().set("sections." + id + ".items", new ArrayList<String>());
        saveConfig();
        loadShops();
        s.sendMessage(color("&aSection &f" + id + " &acreated. Add items with /dshop additem " + id
                + " <MATERIAL> <buy> <sell>"));
    }

    private void removeSection(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(color("&eUsage: /dshop removesection <id>"));
            return;
        }
        String id = a[1].toLowerCase();
        if (!shops.containsKey(id)) {
            s.sendMessage(color("&cSection not found: " + id));
            return;
        }
        getConfig().set("sections." + id, null);
        saveConfig();
        loadShops();
        s.sendMessage(color("&aSection &f" + id + " &aremoved."));
    }

    private void addItem(CommandSender s, String[] a) {
        if (a.length < 5) {
            s.sendMessage(color("&eUsage: /dshop additem <section> <MATERIAL|hand> <buy> <sell>"));
            return;
        }
        String id = a[1].toLowerCase();
        if (!shops.containsKey(id)) {
            s.sendMessage(color("&cSection not found: " + id));
            return;
        }
        Material m = resolveMaterial(s, a[2]);
        if (m == null) return;
        double[] prices = parsePrices(s, a[3], a[4]);
        if (prices == null) return;

        List<String> lines = new ArrayList<>(getConfig().getStringList("sections." + id + ".items"));
        if (findLine(lines, m) >= 0) {
            s.sendMessage(color("&cThat item is already in " + id + ". Use /dshop setprice instead."));
            return;
        }
        if (lines.size() >= MAX_ITEMS) {
            s.sendMessage(color("&cThis section is full (" + MAX_ITEMS + " items)."));
            return;
        }
        lines.add(m.name() + " " + num(prices[0]) + " " + num(prices[1]));
        getConfig().set("sections." + id + ".items", lines);
        saveConfig();
        loadShops();
        s.sendMessage(color("&aAdded &f" + m.name() + " &ato &f" + id + " &7(buy "
                + num(prices[0]) + ", sell " + num(prices[1]) + ")"));
    }

    private void setPrice(CommandSender s, String[] a) {
        if (a.length < 5) {
            s.sendMessage(color("&eUsage: /dshop setprice <section> <MATERIAL|hand> <buy> <sell>"));
            return;
        }
        String id = a[1].toLowerCase();
        if (!shops.containsKey(id)) {
            s.sendMessage(color("&cSection not found: " + id));
            return;
        }
        Material m = resolveMaterial(s, a[2]);
        if (m == null) return;
        double[] prices = parsePrices(s, a[3], a[4]);
        if (prices == null) return;

        List<String> lines = new ArrayList<>(getConfig().getStringList("sections." + id + ".items"));
        int idx = findLine(lines, m);
        if (idx < 0) {
            s.sendMessage(color("&cThat item is not in " + id + ". Use /dshop additem first."));
            return;
        }
        lines.set(idx, m.name() + " " + num(prices[0]) + " " + num(prices[1]));
        getConfig().set("sections." + id + ".items", lines);
        saveConfig();
        loadShops();
        s.sendMessage(color("&aUpdated &f" + m.name() + " &7(buy " + num(prices[0])
                + ", sell " + num(prices[1]) + ")"));
    }

    private void removeItem(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage(color("&eUsage: /dshop removeitem <section> <MATERIAL|hand>"));
            return;
        }
        String id = a[1].toLowerCase();
        if (!shops.containsKey(id)) {
            s.sendMessage(color("&cSection not found: " + id));
            return;
        }
        Material m = resolveMaterial(s, a[2]);
        if (m == null) return;

        List<String> lines = new ArrayList<>(getConfig().getStringList("sections." + id + ".items"));
        int idx = findLine(lines, m);
        if (idx < 0) {
            s.sendMessage(color("&cThat item is not in " + id + "."));
            return;
        }
        lines.remove(idx);
        getConfig().set("sections." + id + ".items", lines);
        saveConfig();
        loadShops();
        s.sendMessage(color("&aRemoved &f" + m.name() + " &afrom &f" + id + "&a."));
    }

    private Material resolveMaterial(CommandSender s, String input) {
        Material m;
        if (input.equalsIgnoreCase("hand")) {
            if (!(s instanceof Player p)) {
                s.sendMessage(color("&cOnly players can use 'hand'."));
                return null;
            }
            m = p.getInventory().getItemInMainHand().getType();
            if (m == Material.AIR) {
                s.sendMessage(color("&cYou aren't holding anything."));
                return null;
            }
        } else {
            m = Material.matchMaterial(input);
        }
        if (m == null || !m.isItem() || m == Material.AIR) {
            s.sendMessage(color("&cUnknown item: " + input));
            return null;
        }
        return m;
    }

    private double[] parsePrices(CommandSender s, String buy, String sell) {
        try {
            double b = Double.parseDouble(buy);
            double c = Double.parseDouble(sell);
            if (b < 0 || c < 0) {
                s.sendMessage(color("&cPrices can't be negative. Use 0 to disable."));
                return null;
            }
            return new double[]{b, c};
        } catch (NumberFormatException e) {
            s.sendMessage(color("&cPrices must be numbers."));
            return null;
        }
    }

    private int findLine(List<String> lines, Material m) {
        for (int i = 0; i < lines.size(); i++) {
            String[] parts = lines.get(i).trim().split("\\s+");
            if (parts.length > 0 && parts[0].equalsIgnoreCase(m.name())) return i;
        }
        return -1;
    }

    private String num(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    // ---------- Tab complete ----------
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("dangshop.admin");

        if (a.length == 1) {
            out.addAll(shops.keySet());
            if (admin) {
                out.addAll(ADMIN_SUBS);
                out.add("reload");
            }
            return filter(out, a[0]);
        }
        if (a.length == 2 && admin) {
            String sub = a[0].toLowerCase();
            if (List.of("removesection", "additem", "setprice", "removeitem", "list").contains(sub)) {
                out.addAll(shops.keySet());
                return filter(out, a[1]);
            }
        }
        return out;
    }

    private List<String> filter(List<String> in, String prefix) {
        List<String> out = new ArrayList<>();
        for (String x : in) {
            if (x.toLowerCase().startsWith(prefix.toLowerCase())) out.add(x);
        }
        return out;
    }

    // ---------- GUI ----------
    private void fill(Inventory inv, int from, int to) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = pane.getItemMeta();
        m.setDisplayName(" ");
        pane.setItemMeta(m);
        for (int i = from; i < to; i++) inv.setItem(i, pane);
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(new Holder(null), SIZE,
                color(getConfig().getString("title", "&8DangShop")));
        fill(inv, 0, SIZE);

        for (String id : shops.keySet()) {
            int slot = slots.get(id);
            if (slot < 0 || slot >= SIZE) continue;
            ItemStack it = new ItemStack(icons.get(id));
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(names.get(id));
            m.setLore(List.of(
                    color("&7" + shops.get(id).size() + " items"),
                    color("&eClick to open")));
            m.addItemFlags(ItemFlag.values());
            it.setItemMeta(m);
            inv.setItem(slot, it);
        }
        p.openInventory(inv);
    }

    private void openSection(Player p, String id) {
        List<ShopItem> list = shops.get(id);
        if (list == null) return;

        Inventory inv = Bukkit.createInventory(new Holder(id), SIZE, names.get(id));
        fill(inv, MAX_ITEMS, SIZE);

        for (int i = 0; i < list.size() && i < MAX_ITEMS; i++) {
            inv.setItem(i, display(list.get(i)));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(color("&cBack"));
        back.setItemMeta(bm);
        inv.setItem(BACK_SLOT, back);

        p.openInventory(inv);
    }

    private ItemStack display(ShopItem si) {
        ItemStack it = new ItemStack(si.mat);
        ItemMeta m = it.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (si.buy > 0) lore.add(color("&aBuy: &f$" + money(si.buy)));
        if (si.sell > 0) lore.add(color("&cSell: &f$" + money(si.sell)));
        lore.add("");
        lore.add(color("&7Left click: buy 1  |  Shift+left: buy 64"));
        lore.add(color("&7Right click: sell 1  |  Shift+right: sell all"));
        m.setLore(lore);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getSlot();

        // Main menu: click a section
        if (h.section == null) {
            for (Map.Entry<String, Integer> s : slots.entrySet()) {
                if (s.getValue() == slot && shops.containsKey(s.getKey())) {
                    String id = s.getKey();
                    Bukkit.getScheduler().runTask(this, () -> openSection(p, id));
                    return;
                }
            }
            return;
        }

        // Inside a section
        if (slot == BACK_SLOT) {
            Bukkit.getScheduler().runTask(this, () -> openMain(p));
            return;
        }
        List<ShopItem> list = shops.get(h.section);
        if (list == null || slot < 0 || slot >= MAX_ITEMS || slot >= list.size()) return;

        ShopItem si = list.get(slot);
        ClickType c = e.getClick();
        if (c.isLeftClick()) {
            buy(p, si, c.isShiftClick() ? 64 : 1);
        } else if (c.isRightClick()) {
            sell(p, si, c.isShiftClick() ? -1 : 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ---------- Buy ---------
