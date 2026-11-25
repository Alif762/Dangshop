package my.dangshop;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
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
        final ItemStack stack;   // template item, amount 1
        final boolean special;   // true = has enchants, name, lore, etc.
        final double buy;
        final double sell;

        ShopItem(ItemStack stack, boolean special, double buy, double sell) {
            this.stack = stack;
            this.special = special;
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

            // Normal items: "MATERIAL BUY SELL"
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
                    list.add(new ShopItem(new ItemStack(m), false,
                            Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
                } catch (NumberFormatException ex) {
                    getLogger().warning("Invalid price in " + id + ": " + line);
                }
            }

            // Special items (enchanted, named, etc.): saved as full items
            ConfigurationSection sp = s.getConfigurationSection("special");
            if (sp != null) {
                for (String key : sp.getKeys(false)) {
                    ItemStack st = sp.getItemStack(key + ".item");
                    if (st == null) {
                        getLogger().warning("Could not read special item " + key + " in " + id);
                        continue;
                    }
                    st = st.clone();
                    st.setAmount(1);
                    list.add(new ShopItem(st, true,
                            sp.getDouble(key + ".buy"), sp.getDouble(key + ".sell")));
                }
            }
            shops.put(id, list);
        }
    }

    // Write a section's items back to config, then reload
    private void persist(String id, List<ShopItem> list) {
        List<String> lines = new ArrayList<>();
        getConfig().set("sections." + id + ".special", null);
        int n = 1;
        for (ShopItem si : list) {
            if (si.special) {
                String base = "sections." + id + ".special." + n++;
                getConfig().set(base + ".item", si.stack);
                getConfig().set(base + ".buy", si.buy);
                getConfig().set(base + ".sell", si.sell);
            } else {
                lines.add(si.stack.getType().name() + " " + num(si.buy) + " " + num(si.sell));
            }
        }
        getConfig().set("sections." + id + ".items", lines);
        saveConfig();
        loadShops();
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
        s.sendMessage(color("&7Hold an enchanted or named item and use 'hand' to sell it exactly as it is."));
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
            s.sendMessage(color("&e" + itemName(si) + " &7buy &a" + num(si.buy)
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
        double[] prices = parsePrices(s, a[3], a[4]);
        if (prices == null) return;
        ShopItem item = make(s, a[2], prices[0], prices[1]);
        if (item == null) return;

        List<ShopItem> list = new ArrayList<>(shops.get(id));
        if (indexOf(list, item) >= 0) {
            s.sendMessage(color("&cThat item is already in " + id + ". Use /dshop setprice instead."));
            return;
        }
        if (list.size() >= MAX_ITEMS) {
            s.sendMessage(color("&cThis section is full (" + MAX_ITEMS + " items)."));
            return;
        }
        list.add(item);
        persist(id, list);
        s.sendMessage(color("&aAdded &f" + itemName(item) + " &ato &f" + id + " &7(buy "
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
        double[] prices = parsePrices(s, a[3], a[4]);
        if (prices == null) return;
        ShopItem probe = make(s, a[2], prices[0], prices[1]);
        if (probe == null) return;

        List<ShopItem> list = new ArrayList<>(shops.get(id));
        int idx = indexOf(list, probe);
        if (idx < 0) {
            s.sendMessage(color("&cThat item is not in " + id + ". Use /dshop additem first."));
            return;
        }
        ShopItem old = list.get(idx);
        list.set(idx, new ShopItem(old.stack, old.special, prices[0], prices[1]));
        persist(id, list);
        s.sendMessage(color("&aUpdated &f" + itemName(old) + " &7(buy " + num(prices[0])
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
        ShopItem probe = make(s, a[2], 0, 0);
        if (probe == null) return;

        List<ShopItem> list = new ArrayList<>(shops.get(id));
        int idx = indexOf(list, probe);
        if (idx < 0) {
            s.sendMessage(color("&cThat item is not in " + id + "."));
            return;
        }
        ShopItem old = list.remove(idx);
        persist(id, list);
        s.sendMessage(color("&aRemoved &f" + itemName(old) + " &afrom &f" + id + "&a."));
    }

    // Build a ShopItem from a material name or the item in the player's hand
    private ShopItem make(CommandSender s, String input, double buy, double sell) {
        if (input.equalsIgnoreCase("hand")) {
            if (!(s instanceof Player p)) {
                s.sendMessage(color("&cOnly players can use 'hand'."));
                return null;
            }
            ItemStack h = p.getInventory().getItemInMainHand();
            if (h == null || h.getType() == Material.AIR) {
                s.sendMessage(color("&cYou aren't holding anything."));
                return null;
            }
            ItemStack copy = h.clone();
            copy.setAmount(1);
            ItemMeta meta = copy.getItemMeta();
            if (meta instanceof Damageable d && d.hasDamage()) {
                d.setDamage(0);
                copy.setItemMeta(meta);
            }
            boolean special = !copy.isSimilar(new ItemStack(copy.getType()));
            return new ShopItem(copy, special, buy, sell);
        }

        Material m = Material.matchMaterial(input);
        if (m == null || !m.isItem() || m == Material.AIR) {
            s.sendMessage(color("&cUnknown item: " + input));
            return null;
        }
        return new ShopItem(new ItemStack(m), false, buy, sell);
    }

    private int indexOf(List<ShopItem> list, ShopItem item) {
        for (int i = 0; i < list.size(); i++) {
            ShopItem x = list.get(i);
            if (x.special != item.special) continue;
            if (item.special ? x.stack.isSimilar(item.stack)
                    : x.stack.getType() == item.stack.getType()) {
                return i;
            }
        }
        return -1;
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

    private String num(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    // Readable item name, including the enchantments of enchanted books
    private String itemName(ShopItem si) {
        ItemMeta m = si.stack.getItemMeta();
        if (m != null && m.hasDisplayName()) return ChatColor.stripColor(m.getDisplayName());

        String base = niceName(si.stack.getType().name());
        if (m instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchants()) {
            StringBuilder sb = new StringBuilder(base).append(" (");
            boolean first = true;
            for (Map.Entry<Enchantment, Integer> e : esm.getStoredEnchants().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(niceName(e.getKey().getKey().getKey())).append(' ').append(e.getValue());
                first = false;
            }
            return sb.append(")").toString();
        }
        return si.special ? base + " (Special)" : base;
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

    // Shows the real item (with its enchants) plus the shop price lines
    private ItemStack display(ShopItem si) {
        ItemStack it = si.stack.clone();
        it.setAmount(1);
        ItemMeta m = it.getItemMeta();

        List<String> lore = new ArrayList<>();
        if (m.hasLore() && m.getLore() != null) lore.addAll(m.getLore());
        lore.add("");
        if (si.buy > 0) lore.add(color("&aBuy: &f$" + money(si.buy)));
        if (si.sell > 0) lore.add(color("&cSell: &f$" + money(si.sell)));
        lore.add("");
        lore.add(color("&7Left click: buy 1  |  Shift+left: buy 64"));
        lore.add(color("&7Right click: sell 1  |  Shift+right: sell all"));
        m.setLore(lore);

        if (!si.special) {
            m.addItemFlags(ItemFlag.values());
        } else {
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
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

    // ---------- Buy ----------
    private void buy(Player p, ShopItem si, int amount) {
        if (si.buy <= 0) {
            p.sendMessage(msg("cant-buy", "&cThis item can't be bought."));
            return;
        }
        if (!hookEconomy()) {
            p.sendMessage(msg("no-economy", "&cEconomy system (Vault) not found."));
            return;
        }
        if (balance(p) < si.buy * amount) {
            p.sendMessage(msg("no-money", "&cYou don't have enough money."));
            return;
        }

        int max = si.stack.getMaxStackSize();
        int left = amount;
        int notFit = 0;
        while (left > 0) {
            int n = Math.min(left, max);
            ItemStack give = si.stack.clone();
            give.setAmount(n);
            Map<Integer, ItemStack> rest = p.getInventory().addItem(give);
            for (ItemStack r : rest.values()) notFit += r.getAmount();
            left -= n;
        }

        int given = amount - notFit;
        if (given <= 0) {
            p.sendMessage(msg("inv-full", "&cYour inventory is full."));
            return;
        }

        double cost = si.buy * given;
        take(p, cost);
        p.sendMessage(msg("bought", "&aBought &f%amount%x %item% &afor &f$%price%")
                .replace("%amount%", String.valueOf(given))
                .replace("%item%", itemName(si))
                .replace("%price%", money(cost)));
    }

    // ---------- Sell (amount -1 = all) ----------
    private void sell(Player p, ShopItem si, int amount) {
        if (si.sell <= 0) {
            p.sendMessage(msg("cant-sell", "&cThis item can't be sold."));
            return;
        }
        if (!hookEconomy()) {
            p.sendMessage(msg("no-economy", "&cEconomy system (Vault) not found."));
            return;
        }

        ItemStack ref = si.stack;
        ItemStack[] contents = p.getInventory().getStorageContents();

        int have = 0;
        for (ItemStack it : contents) {
            if (it != null && it.isSimilar(ref)) have += it.getAmount();
        }
        if (have <= 0) {
            p.sendMessage(msg("no-items", "&cYou don't have that item."));
            return;
        }

        int n = amount < 0 ? have : Math.min(amount, have);
        int remaining = n;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !it.isSimilar(ref)) continue;
            int takeAmt = Math.min(remaining, it.getAmount());
            if (takeAmt >= it.getAmount()) {
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - takeAmt);
            }
            remaining -= takeAmt;
        }
        p.getInventory().setStorageContents(contents);

        double gain = si.sell * n;
        give(p, gain);
        p.sendMessage(msg("sold", "&aSold &f%amount%x %item% &afor &f$%price%")
                .replace("%amount%", String.valueOf(n))
                .replace("%item%", itemName(si))
                .replace("%price%", money(gain)));
    }

    // ---------- Money (Vault) ----------
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean hookEconomy() {
        if (eco != null) return true;
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return false;
            eco = rsp.getProvider();
            getBalance = c.getMethod("getBalance", OfflinePlayer.class);
            withdraw = c.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = c.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private double balance(Player p) {
        try {
            return ((Number) getBalance.invoke(eco, p)).doubleValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void take(Player p, double amount) {
        try {
            withdraw.invoke(eco, p, amount);
        } catch (Throwable t) {
            getLogger().warning("Failed to withdraw money: " + t.getMessage());
        }
    }

    private void give(Player p, double amount) {
        try {
            deposit.invoke(eco, p, amount);
        } catch (Throwable t) {
            getLogger().warning("Failed to deposit money: " + t.getMessage());
        }
    }

    // ---------- Utilities ----------
    private String money(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    private String niceName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String w : raw.toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
                                          }
